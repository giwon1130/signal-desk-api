package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.events.application.MarketEventService
import com.giwon.signaldesk.features.market.application.CboeVixClient
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.KrxOfficialClient
import com.giwon.signaldesk.features.market.application.NaverInvestorRankClient
import com.giwon.signaldesk.features.market.application.TopMoversService
import com.giwon.signaldesk.features.market.application.UsIndexService
import com.giwon.signaldesk.features.market.application.YahooQuoteClient
import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * KR 장중(12:30)·마감(15:40) 브리프 — 모닝 브리프와 같은 데이터 파이프라인을 KR 관점으로 재해석.
 *
 * 모닝 브리프와 차이:
 *  - 보유 공시 매칭 / 푸시 발송 없음 (앱 브리프 카드 갱신 전용 — 알림 스팸 방지).
 *  - slot 에 따라 프롬프트·source·제목만 달라짐.
 *
 * 앱은 /api/v1/media/summaries/latest 로 최신 브리프를 보여주므로, 시간대별로 가장 최근 브리프가 노출된다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class IntradayBriefService(
    private val vixClient: CboeVixClient,
    private val fredIndexClient: FredIndexClient,
    private val usIndexService: UsIndexService,
    private val newsRssClient: GoogleNewsRssClient,
    private val investorRankClient: NaverInvestorRankClient,
    private val krxOfficialClient: KrxOfficialClient,
    private val topMoversService: TopMoversService,
    private val yahooQuoteClient: YahooQuoteClient,
    private val geminiClient: GeminiClient,
    private val marketEventService: MarketEventService,
    private val repository: MediaSummaryRepository,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val expoPushClient: ExpoPushClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    enum class Slot(
        val source: MediaSource,
        val channelTitle: String,
        val videoPrefix: String,
        val titleSuffix: String,
        val prefColumn: String,   // alert_preferences 토글 컬럼
        val pushType: String,     // 푸시 data.type
        val defaultOn: Boolean,   // 미설정 사용자 기본 알림 여부
    ) {
        MIDDAY(MediaSource.MIDDAY_BRIEF, "장중 브리프", "midday", "장중 브리프", "midday_brief_enabled", "MIDDAY_BRIEF", false),
        CLOSE(MediaSource.CLOSE_BRIEF, "마감 브리프", "close", "마감 브리프", "close_brief_enabled", "CLOSE_BRIEF", true),
    }

    /** 단일 실행. force=true 면 기존 brief 가 있어도 재생성. */
    fun runBrief(slot: Slot, force: Boolean = false): MediaSummary? {
        if (!geminiClient.isEnabled()) {
            log.warn("IntradayBrief({}) skipped — GEMINI_API_KEY 미설정", slot)
            return null
        }
        val today = LocalDate.now(clock)
        val videoId = "${slot.videoPrefix}-${today.format(dateFmt)}"
        if (!force && repository.findByVideoId(videoId) != null) {
            log.info("IntradayBrief({}) already exists. videoId={}", slot, videoId)
            return null
        }

        // 모닝 브리프와 동일한 외부 API 병렬 수집.
        val vixF = supplyAsync { vixClient.fetchVix() }
        val indicesF = supplyAsync { usIndexService.fetchUsIndices() }
        val macroF = supplyAsync { fredIndexClient.fetchMacro() }
        val headlinesF = supplyAsync { newsRssClient.fetchMarketNews() }
        val eventsF = supplyAsync { marketEventService.upcoming(3) }
        val flowF = supplyAsync { investorRankClient.fetchFlowSnapshot(limit = 7) }
        val krMarketF = supplyAsync { krxOfficialClient.loadKoreaMarketSection() }
        val krMoversF = supplyAsync { topMoversService.fetchTopMovers(5) }
        val globalF = supplyAsync { yahooQuoteClient.fetchIndices(YahooQuoteClient.GLOBAL_INDICES) }

        val vix = vixF.join()
        val indices = indicesF.join()
        val macro = macroF.join()
        val headlines = headlinesF.join() ?: emptyList()
        val upcomingEvents = eventsF.join() ?: emptyList()
        val investorFlow = flowF.join()
        val krMarket = krMarketF.join()
        val krMovers = krMoversF.join()
        val krGainers = krMovers?.let { (it.kospi.gainers + it.kosdaq.gainers).sortedByDescending { m -> m.changeRate }.take(5) } ?: emptyList()
        val krLosers = krMovers?.let { (it.kospi.losers + it.kosdaq.losers).sortedBy { m -> m.changeRate }.take(5) } ?: emptyList()
        val global = globalF.join() ?: emptyList()

        // 운영 관측 — 데이터 소스 충족 현황 한 줄.
        log.info(
            "IntradayBrief({}) sources: vix={}, usIdx={}, macro={}, krMarket={}, krMovers={}+{}, global={}, flow={}, headlines={}, events={}",
            slot, vix != null, indices != null, macro != null, krMarket != null,
            krGainers.size, krLosers.size, global.size, investorFlow != null, headlines.size, upcomingEvents.size,
        )

        val analysis = runCatching {
            geminiClient.summarizeIntradayBrief(
                slot = slot.name,
                vix = vix, indices = indices, macro = macro, headlines = headlines,
                investorFlow = investorFlow, upcomingEvents = upcomingEvents,
                krMarket = krMarket, krGainers = krGainers, krLosers = krLosers, global = global,
            )
        }.getOrElse {
            log.warn("IntradayBrief({}) Gemini call failed", slot, it)
            null
        } ?: run {
            log.warn("IntradayBrief({}) Gemini returned null", slot)
            return null
        }

        val title = "${today.format(DateTimeFormatter.ofPattern("M월 d일"))} ${slot.titleSuffix}"
        val summary = MediaSummary(
            id = UUID.randomUUID().toString(),
            channelId = slot.videoPrefix + "-brief",
            channelTitle = slot.channelTitle,
            videoId = videoId,
            videoTitle = title,
            videoUrl = "",
            publishedAt = Instant.now(),
            transcriptLength = headlines.size,
            summary = analysis.headline + "\n\n" + analysis.summary,
            flowAnalysis = analysis.keyPoints.joinToString("\n") { "• $it" },
            keyTickers = emptyList(),
            sentiment = analysis.sentiment,
            hasTranscript = true,
            source = slot.source,
            createdAt = Instant.now(),
        )
        val saved = repository.save(summary)
        log.info("IntradayBrief({}) saved. videoId={}, headline={}", slot, videoId, analysis.headline)

        runCatching { dispatchPush(slot, analysis) }
            .onFailure { log.warn("IntradayBrief({}) push failed", slot, it) }
        return saved
    }

    /** slot 토글 ON 사용자에게만 브리프 푸시 (기본 OFF — 켠 사람만). */
    private fun dispatchPush(slot: Slot, analysis: MarketInsightAnalysis) {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return
        val enabledUsers = alertPreferenceService.loadIntradayBriefEnabledUsers(slot.prefColumn, slot.defaultOn)
        val targets = devicesByUser.filterKeys { it in enabledUsers }
        if (targets.isEmpty()) return

        val emoji = when (analysis.sentiment) {
            MediaSentiment.BULLISH -> "🟢"
            MediaSentiment.BEARISH -> "🔴"
            MediaSentiment.NEUTRAL -> "🟡"
        }
        val title = "$emoji ${analysis.headline.ifBlank { slot.titleSuffix }}"
        val body = analysis.summary.take(180)
        val messages = targets.flatMap { (_, devices) ->
            devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf("type" to slot.pushType),
                )
            }
        }
        expoPushClient.send(messages)
        log.info("IntradayBrief({}) push dispatched. recipients={}, messages={}", slot, targets.size, messages.size)
    }

    private fun <T> supplyAsync(block: () -> T?): CompletableFuture<T?> =
        CompletableFuture.supplyAsync { runCatching(block).getOrNull() }
}
