package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.disclosure.application.DisclosureSeenRepository
import com.giwon.signaldesk.features.events.application.MarketEventService
import com.giwon.signaldesk.features.market.application.CboeVixClient
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.NaverInvestorRankClient
import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 08:30 KST 모닝 브리프 — 야간 미국장 + KR/US 뉴스 + 보유/관심 종목 공시를 Gemini 가 종합.
 *
 * 흐름:
 *   1) 사용자별 보유/관심 KR 종목 stock_code 수집
 *   2) DART seen 테이블에서 어제 + 오늘 공시 중 보유/관심 종목 매칭분 추출
 *   3) VIX / FRED 지수 / 뉴스 헤드라인 수집
 *   4) Gemini 1회 호출 (모든 사용자 공통 시장 요약)
 *   5) media_summaries 에 source=MORNING_BRIEF, videoId="brief-YYYY-MM-DD" 로 upsert
 *   6) 알림 ON 사용자에게 푸시 — 본인 보유 공시 갯수 prefix 로 개인화
 *
 * Premarket alert (08:00/08:30 단순 변동률 푸시) 는 이 서비스로 통합되어 제거됨.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MorningBriefService(
    private val jdbc: JdbcTemplate,
    private val vixClient: CboeVixClient,
    private val fredIndexClient: FredIndexClient,
    private val newsRssClient: GoogleNewsRssClient,
    private val investorRankClient: NaverInvestorRankClient,
    private val geminiClient: GeminiClient,
    private val marketEventService: MarketEventService,
    private val disclosureSeenRepository: DisclosureSeenRepository,
    private val repository: MediaSummaryRepository,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val expoPushClient: ExpoPushClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 단일 실행. force=true 면 기존 brief 가 있어도 재생성. */
    fun runBrief(force: Boolean = false): MediaSummary? {
        if (!geminiClient.isEnabled()) {
            log.warn("MorningBrief skipped — GEMINI_API_KEY 미설정")
            return null
        }
        val today = LocalDate.now(clock)
        val videoId = "brief-${today.format(dateFmt)}"
        if (!force && repository.findByVideoId(videoId) != null) {
            log.info("MorningBrief already exists. videoId={}", videoId)
            return null
        }

        // 사용자 보유/관심 종목 + 매칭 공시
        val userTickers = loadAllUserKrTickers()  // Map<UUID, Set<String>>
        val allTickers = userTickers.values.flatten().toSet()
        val matchedDisclosures = if (allTickers.isNotEmpty()) {
            disclosureSeenRepository.findRecentByStockCodes(allTickers, limit = 50)
                .filter { isWithinOvernightWindow(it.rceptDt, today) }
        } else emptyList()

        val vix = runCatching { vixClient.fetchVix() }.getOrNull()
        val indices = runCatching { fredIndexClient.fetchUsIndices() }.getOrNull()
        val macro = runCatching { fredIndexClient.fetchMacro() }.getOrNull()
        val headlines = runCatching { newsRssClient.fetchMarketNews() }.getOrNull() ?: emptyList()
        val upcomingEvents = runCatching { marketEventService.upcoming(3) }.getOrNull() ?: emptyList()
        val investorFlow = runCatching { investorRankClient.fetchFlowSnapshot(limit = 7) }.getOrNull()

        val disclosureTitles = matchedDisclosures.map { "[${it.corpName}] ${it.reportNm}" }
        val analysis = runCatching {
            geminiClient.summarizeMorningBrief(
                vix = vix, indices = indices, macro = macro, headlines = headlines,
                disclosureTitles = disclosureTitles,
                investorFlow = investorFlow,
                upcomingEvents = upcomingEvents,
            )
        }.getOrElse {
            log.warn("MorningBrief Gemini call failed", it)
            null
        } ?: run {
            log.warn("MorningBrief Gemini returned null")
            return null
        }

        val title = "${today.format(DateTimeFormatter.ofPattern("M월 d일"))} 모닝 브리프"
        val summary = MediaSummary(
            id = UUID.randomUUID().toString(),
            channelId = "morning-brief",
            channelTitle = "모닝 브리프",
            videoId = videoId,
            videoTitle = title,
            videoUrl = "",
            publishedAt = Instant.now(),
            transcriptLength = disclosureTitles.sumOf { it.length } + headlines.size,
            summary = analysis.headline + "\n\n" + analysis.summary,
            flowAnalysis = analysis.keyPoints.joinToString("\n") { "• $it" },
            keyTickers = matchedDisclosures.map { it.stockCode }.distinct().take(6),
            sentiment = analysis.sentiment,
            hasTranscript = true,
            source = MediaSource.MORNING_BRIEF,
            createdAt = Instant.now(),
        )
        val saved = repository.save(summary)
        log.info("MorningBrief saved. videoId={}, headline={}, disclosures={}",
            videoId, analysis.headline, matchedDisclosures.size)

        dispatchPushes(analysis, matchedDisclosures, userTickers)
        return saved
    }

    /** rceptDt(YYYYMMDD) 가 어제 또는 오늘에 해당하면 야간 공시로 간주. */
    private fun isWithinOvernightWindow(rceptDt: String, today: LocalDate): Boolean {
        if (rceptDt.length != 8 || !rceptDt.all(Char::isDigit)) return false
        val yesterday = today.minusDays(1)
        val todayStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val yesterdayStr = yesterday.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        return rceptDt == todayStr || rceptDt == yesterdayStr
    }

    private fun loadAllUserKrTickers(): Map<UUID, Set<String>> {
        val watch = jdbc.query(
            "select user_id, ticker from signal_desk_watchlist where market = 'KR' and user_id is not null",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
        )
        val portfolio = jdbc.query(
            "select user_id, ticker from signal_desk_portfolio_positions where market = 'KR' and user_id is not null",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
        )
        return (watch + portfolio)
            .filter { it.second.length == 6 && it.second.all(Char::isDigit) }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    private fun dispatchPushes(
        analysis: MarketInsightAnalysis,
        disclosures: List<com.giwon.signaldesk.features.disclosure.application.Disclosure>,
        userTickers: Map<UUID, Set<String>>,
    ) {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return
        // premarket_enabled 컬럼을 그대로 "모닝브리프 ON" 으로 재해석한다 (앱 단의 토글 라벨만 바뀜).
        val enabledUsers = alertPreferenceService.loadEnabledUsers(market = "KR", includePremarket = true)
        val targets = devicesByUser.filterKeys { it in enabledUsers }
        if (targets.isEmpty()) return

        val disclosuresByTicker = disclosures.groupBy { it.stockCode }
        var sent = 0
        for ((userId, devices) in targets) {
            val myTickers = userTickers[userId].orEmpty()
            val myDisclosures = myTickers.flatMap { disclosuresByTicker[it].orEmpty() }
            val (title, body) = buildPushMessage(analysis, myDisclosures)
            val msgs = devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf("type" to "MORNING_BRIEF"),
                )
            }
            expoPushClient.send(msgs)
            sent += msgs.size
        }
        log.info("MorningBrief push dispatched. recipients={}, messages={}", targets.size, sent)
    }

    private fun buildPushMessage(
        analysis: MarketInsightAnalysis,
        myDisclosures: List<com.giwon.signaldesk.features.disclosure.application.Disclosure>,
    ): Pair<String, String> {
        val emoji = when (analysis.sentiment) {
            MediaSentiment.BULLISH -> "🟢"
            MediaSentiment.BEARISH -> "🔴"
            MediaSentiment.NEUTRAL -> "🟡"
        }
        val title = "$emoji ${analysis.headline.ifBlank { "오늘의 모닝 브리프" }}"
        val prefix = if (myDisclosures.isNotEmpty()) {
            val names = myDisclosures.map { it.corpName }.distinct().take(2).joinToString(", ")
            "📢 보유 ${myDisclosures.size}건(${names}) · "
        } else ""
        val body = (prefix + analysis.summary).take(180)
        return title to body
    }
}
