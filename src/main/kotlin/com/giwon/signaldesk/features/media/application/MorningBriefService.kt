package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.disclosure.application.Disclosure
import com.giwon.signaldesk.features.disclosure.application.DisclosureSeenRepository
import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.PushRepository
import com.giwon.signaldesk.features.workspace.application.UserWatchTickerRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 08:30 KST 모닝 브리프 — 야간 미국장 + KR/US 뉴스 + 보유/관심 종목 공시를 Gemini 가 종합.
 *
 * 흐름 (공통 골격은 [BriefPipeline]):
 *   1) 사용자별 보유/관심 KR 종목 stock_code 수집
 *   2) DART seen 테이블에서 어제 + 오늘 공시 중 보유/관심 종목 매칭분 추출
 *   3) VIX / FRED 지수 / 뉴스 헤드라인 + 실적 캘린더 병렬 수집
 *   4) Gemini 1회 호출 (모든 사용자 공통 시장 요약)
 *   5) media_summaries 에 source=MORNING_BRIEF, videoId="brief-YYYY-MM-DD" 로 upsert
 *   6) 알림 ON 사용자에게 푸시 — 본인 보유 공시 갯수 prefix 로 개인화
 *
 * Premarket alert (08:00/08:30 단순 변동률 푸시) 는 이 서비스로 통합되어 제거됨.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MorningBriefService(
    private val pipeline: BriefPipeline,
    private val geminiClient: GeminiClient,
    private val userWatchTickers: UserWatchTickerRepository,
    private val disclosureSeenRepository: DisclosureSeenRepository,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val slotConfig = BriefPipeline.SlotConfig(
        logLabel = "MorningBrief",
        videoPrefix = "brief",
        channelId = "morning-brief",
        channelTitle = "모닝 브리프",
        titleSuffix = "모닝 브리프",
        source = MediaSource.MORNING_BRIEF,
    )

    /** 모닝 브리프 고유 사전 단계 결과 — 사용자별 보유/관심 종목 + 야간 매칭 공시. */
    private data class MorningContext(
        val userTickers: Map<UUID, Set<String>>,
        val matchedDisclosures: List<Disclosure>,
    ) {
        val disclosureTitles: List<String> = matchedDisclosures.map { "[${it.corpName}] ${it.reportNm}" }
    }

    /** 단일 실행. force=true 면 기존 brief 가 있어도 재생성. */
    fun runBrief(force: Boolean = false): MediaSummary? {
        val today = LocalDate.now(clock)
        return pipeline.run(
            config = slotConfig,
            today = today,
            force = force,
            includeEarnings = true,
            prepare = {
                // 사용자 보유/관심 종목 + 매칭 공시 (어제~오늘 야간 공시만)
                val userTickers = userWatchTickers.tickersByUser(market = "KR")  // Map<UUID, Set<String>>
                val allTickers = userTickers.values.flatten().toSet()
                val matched = if (allTickers.isNotEmpty()) {
                    disclosureSeenRepository.findRecentByStockCodes(allTickers, limit = 50)
                        .filter { isWithinOvernightWindow(it.rceptDt, today) }
                } else emptyList()
                MorningContext(userTickers, matched)
            },
            disclosureCount = { it.matchedDisclosures.size },
            analyze = { ctx, d ->
                geminiClient.summarizeMorningBrief(
                    vix = d.vix, indices = d.indices, macro = d.macro, headlines = d.headlines,
                    disclosureTitles = ctx.disclosureTitles,
                    investorFlow = d.investorFlow,
                    upcomingEvents = d.upcomingEvents,
                    krMarket = d.krMarket, krGainers = d.krGainers, krLosers = d.krLosers,
                    earningsSymbols = d.earningsSymbols, global = d.global,
                )
            },
            transcriptLength = { ctx, d -> ctx.disclosureTitles.sumOf { it.length } + d.headlines.size },
            keyTickers = { ctx -> ctx.matchedDisclosures.map { it.stockCode }.distinct().take(6) },
            dispatchPush = { ctx, analysis -> dispatchPushes(analysis, ctx.matchedDisclosures, ctx.userTickers) },
        )
    }

    /** rceptDt(YYYYMMDD) 가 어제 또는 오늘에 해당하면 야간 공시로 간주. */
    private fun isWithinOvernightWindow(rceptDt: String, today: LocalDate): Boolean {
        if (rceptDt.length != 8 || !rceptDt.all(Char::isDigit)) return false
        val yesterday = today.minusDays(1)
        val todayStr = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val yesterdayStr = yesterday.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        return rceptDt == todayStr || rceptDt == yesterdayStr
    }

    private fun dispatchPushes(
        analysis: MarketInsightAnalysis,
        disclosures: List<Disclosure>,
        userTickers: Map<UUID, Set<String>>,
    ) {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return
        // premarket_enabled 컬럼을 그대로 "모닝브리프 ON" 으로 재해석한다 (앱 단의 토글 라벨만 바뀜).
        val enabledUsers = alertPreferenceService.loadEnabledUsers(market = "KR", includePremarket = true)
        val targets = devicesByUser.filterKeys { it in enabledUsers }
        if (targets.isEmpty()) return

        val disclosuresByTicker = disclosures.groupBy { it.stockCode }
        // 사용자별 본인 보유 공시 prefix 로 개인화한 메시지를 일괄 발송.
        val sent = pipeline.sendToTargets(targets, pushType = "MORNING_BRIEF") { userId ->
            val myTickers = userTickers[userId].orEmpty()
            val myDisclosures = myTickers.flatMap { disclosuresByTicker[it].orEmpty() }
            buildPushMessage(analysis, myDisclosures)
        }
        log.info("MorningBrief push dispatched. recipients={}, messages={}", targets.size, sent)
    }

    private fun buildPushMessage(
        analysis: MarketInsightAnalysis,
        myDisclosures: List<Disclosure>,
    ): Pair<String, String> {
        val title = "${pipeline.sentimentEmoji(analysis.sentiment)} ${analysis.headline.ifBlank { "오늘의 모닝 브리프" }}"
        val prefix = if (myDisclosures.isNotEmpty()) {
            val names = myDisclosures.map { it.corpName }.distinct().take(2).joinToString(", ")
            "📢 보유 ${myDisclosures.size}건(${names}) · "
        } else ""
        val body = (prefix + analysis.summary).take(180)
        return title to body
    }
}
