package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.events.application.FinnhubClient
import com.giwon.signaldesk.features.market.application.CboeVixClient
import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.UsIndexService
import com.giwon.signaldesk.features.market.application.UsIndicesSnapshot
import com.giwon.signaldesk.features.market.application.YahooFinanceScreenerClient
import com.giwon.signaldesk.features.market.application.YahooQuote
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
 * US 이브닝 브리프 — NY 장 마감 직후(06:30 KST) NASDAQ/S&P 변동·주도주·실적·뉴스를 Gemini로 종합해 한 줄 푸시.
 * Gemini 키 없으면 template 합성으로 fallback.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class EveningBriefService(
    private val cboeVixClient: CboeVixClient,
    private val usIndexService: UsIndexService,
    private val yahooFinanceScreenerClient: YahooFinanceScreenerClient,
    private val finnhubClient: FinnhubClient,
    private val googleNewsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
    private val expoPushClient: ExpoPushClient,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val mediaSummaryRepository: MediaSummaryRepository,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun runBrief() {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return
        val enabledUsers = alertPreferenceService.loadEveningBriefEnabledUsers()
        val targets = devicesByUser.filterKeys { it in enabledUsers }
        if (targets.isEmpty()) {
            log.info("Evening brief — no opted-in recipients")
            return
        }

        // 외부 데이터 병렬 수집 — 모닝 브리프와 동일 패턴.
        val vixF = supplyAsync { cboeVixClient.fetchVix() }
        val indicesF = supplyAsync { usIndexService.fetchUsIndices() }
        val gainersF = supplyAsync { yahooFinanceScreenerClient.fetchGainers(5) }
        val losersF = supplyAsync { yahooFinanceScreenerClient.fetchLosers(5) }
        val today = LocalDate.now(clock)
        val earningsF = supplyAsync {
            finnhubClient.fetchEarningsCalendar(today.minusDays(1).toString(), today.toString())
        }
        val headlinesF = supplyAsync { googleNewsRssClient.fetchMarketNews() }

        val vix = vixF.join()
        val indices = indicesF.join()
        val gainers = gainersF.join() ?: emptyList()
        val losers = losersF.join() ?: emptyList()
        val earnings = earningsF.join() ?: emptyList()
        val headlines = headlinesF.join() ?: emptyList()

        // Gemini 합성 시도 → 실패 시 template fallback.
        val analysis = if (geminiClient.isEnabled()) {
            geminiClient.summarizeEveningBrief(
                vix = vix,
                indices = indices,
                topGainers = gainers,
                topLosers = losers,
                earningsSymbols = earnings.map { it.symbol }.distinct(),
                headlines = headlines,
            )
        } else null

        val (title, body) = if (analysis != null) {
            buildGeminiMessage(analysis)
        } else {
            buildTemplateMessage(indices, gainers, losers, earnings.size)
        }

        // Gemini 합성 성공한 경우만 MediaSummary 저장 — template fallback 은 keyPoints 등이 없어 카드 UI 가 빈약함.
        // /api/v1/media/summaries/latest 가 EVENING_BRIEF 도 자동으로 픽업.
        if (analysis != null) {
            saveMediaSummary(today, analysis, gainers, losers)
        }

        val messages = targets.flatMap { (_, devices) ->
            devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf("type" to "EVENING_BRIEF"),
                )
            }
        }
        expoPushClient.send(messages)
        log.info(
            "Evening brief dispatched. recipients={}, messages={}, source={}",
            targets.size, messages.size, if (analysis != null) "gemini" else "template",
        )
    }

    private fun buildGeminiMessage(analysis: MarketInsightAnalysis): Pair<String, String> {
        val emoji = when (analysis.sentiment) {
            MediaSentiment.BULLISH -> "🟢"
            MediaSentiment.BEARISH -> "🔴"
            MediaSentiment.NEUTRAL -> "🟡"
        }
        val title = "$emoji ${analysis.headline.ifBlank { "미국장 마감 브리프" }}"
        val body = analysis.summary.take(180)
        return title to body
    }

    private fun buildTemplateMessage(
        indices: UsIndicesSnapshot?,
        gainers: List<YahooQuote>,
        losers: List<YahooQuote>,
        earningsCount: Int,
    ): Pair<String, String> {
        val nasdaqRate = indices?.nasdaq?.changeRate
        val sp500Rate = indices?.sp500?.changeRate
        val emoji = when {
            nasdaqRate != null && nasdaqRate <= -1.0 -> "🔴"
            nasdaqRate != null && nasdaqRate >= 1.0 -> "🟢"
            else -> "🟡"
        }
        val title = "$emoji 미국장 마감 브리프"

        val parts = mutableListOf<String>()
        if (nasdaqRate != null && sp500Rate != null) {
            parts += "NASDAQ ${formatRate(nasdaqRate)} · S&P ${formatRate(sp500Rate)}"
        }
        val topGain = gainers.firstOrNull()
        val topLoss = losers.firstOrNull()
        if (topGain != null) parts += "🚀${topGain.ticker} ${formatRate(topGain.changeRate)}"
        if (topLoss != null) parts += "⚠️${topLoss.ticker} ${formatRate(topLoss.changeRate)}"
        if (earningsCount > 0) parts += "실적 ${earningsCount}건"

        val body = parts.joinToString(" · ").ifBlank { "장 마감 데이터 확인 중" }.take(180)
        return title to body
    }

    private fun formatRate(r: Double): String = if (r >= 0) "+${"%.2f".format(r)}%" else "${"%.2f".format(r)}%"

    /**
     * MediaSummary 로 저장 — 앱 TodayTab 의 MediaSummaryCard 가 /summaries/latest 로 받아 렌더.
     * 같은 날 재실행 시 같은 videoId 로 upsert (Repository.save 가 upsert 처리).
     */
    private fun saveMediaSummary(
        today: LocalDate,
        analysis: MarketInsightAnalysis,
        gainers: List<YahooQuote>,
        losers: List<YahooQuote>,
    ) {
        val videoId = "evening-${today.format(dateFmt)}"
        if (mediaSummaryRepository.findByVideoId(videoId) != null) {
            log.info("EveningBrief MediaSummary already exists. videoId={}", videoId)
            return
        }
        val title = "${today.format(DateTimeFormatter.ofPattern("M월 d일"))} 미국장 마감 브리프"
        // 주도주(상승·하락) 상위 6개를 keyTickers 로 — MediaSummaryCard 에서 chip 으로 렌더.
        val keyTickers = (gainers + losers).take(6).map { it.ticker }.distinct()
        val summary = MediaSummary(
            id = UUID.randomUUID().toString(),
            channelId = "evening-brief",
            channelTitle = "미국장 마감 브리프",
            videoId = videoId,
            videoTitle = title,
            videoUrl = "",
            publishedAt = Instant.now(),
            transcriptLength = analysis.summary.length + analysis.keyPoints.sumOf { it.length },
            summary = analysis.headline + "\n\n" + analysis.summary,
            flowAnalysis = analysis.keyPoints.joinToString("\n") { "• $it" },
            keyTickers = keyTickers,
            sentiment = analysis.sentiment,
            hasTranscript = true,
            source = MediaSource.EVENING_BRIEF,
            createdAt = Instant.now(),
        )
        mediaSummaryRepository.save(summary)
        log.info("EveningBrief MediaSummary saved. videoId={}, keyTickers={}", videoId, keyTickers.size)
    }

    private fun <T> supplyAsync(block: () -> T?): CompletableFuture<T?> =
        CompletableFuture.supplyAsync { runCatching(block).getOrNull() }
}
