package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.events.application.FinnhubClient
import com.giwon.signaldesk.features.market.application.CboeVixClient
import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.UsIndexService
import com.giwon.signaldesk.features.market.application.UsIndicesSnapshot
import com.giwon.signaldesk.features.market.application.YahooFinanceScreenerClient
import com.giwon.signaldesk.features.market.application.YahooQuote
import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * US 이브닝 브리프 — NY 장 마감 직후(06:30 KST) NASDAQ/S&P 변동·주도주·실적·뉴스를 Gemini로 종합해 한 줄 푸시.
 * Gemini 키 없으면 template 합성으로 fallback.
 *
 * US 전용 데이터 소스(스크리너 급등락·전일 실적)와 fallback 푸시 정책 때문에
 * [BriefPipeline.run] 골격은 쓰지 않고 공용 단계(병렬 수집 헬퍼·요약 조립·푸시 발송)만 재사용한다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class EveningBriefService(
    private val pipeline: BriefPipeline,
    private val cboeVixClient: CboeVixClient,
    private val usIndexService: UsIndexService,
    private val yahooFinanceScreenerClient: YahooFinanceScreenerClient,
    private val finnhubClient: FinnhubClient,
    private val googleNewsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
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

        // 외부 데이터 병렬 수집 — 모닝 브리프와 동일 패턴 (US 전용 소스라 BriefPipeline 수집 단계와는 별도).
        val vixF = pipeline.supplyAsync { cboeVixClient.fetchVix() }
        val indicesF = pipeline.supplyAsync { usIndexService.fetchUsIndices() }
        val gainersF = pipeline.supplyAsync { yahooFinanceScreenerClient.fetchGainers(5) }
        val losersF = pipeline.supplyAsync { yahooFinanceScreenerClient.fetchLosers(5) }
        val today = LocalDate.now(clock)
        val earningsF = pipeline.supplyAsync {
            finnhubClient.fetchEarningsCalendar(today.minusDays(1).toString(), today.toString())
        }
        val headlinesF = pipeline.supplyAsync { googleNewsRssClient.fetchMarketNews() }

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
            pipeline.briefPushContent(analysis, fallbackTitle = "미국장 마감 브리프")
        } else {
            buildTemplateMessage(indices, gainers, losers, earnings.size)
        }

        // Gemini 합성 성공한 경우만 MediaSummary 저장 — template fallback 은 keyPoints 등이 없어 카드 UI 가 빈약함.
        // /api/v1/media/summaries/latest 가 EVENING_BRIEF 도 자동으로 픽업.
        if (analysis != null) {
            saveMediaSummary(today, analysis, gainers, losers)
        }

        val sent = pipeline.sendToTargets(targets, pushType = "EVENING_BRIEF") { title to body }
        log.info(
            "Evening brief dispatched. recipients={}, messages={}, source={}",
            targets.size, sent, if (analysis != null) "gemini" else "template",
        )
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
        // 주도주(상승·하락) 상위 6개를 keyTickers 로 — MediaSummaryCard 에서 chip 으로 렌더.
        val keyTickers = (gainers + losers).take(6).map { it.ticker }.distinct()
        val summary = pipeline.buildSummary(
            videoId = videoId,
            channelId = "evening-brief",
            channelTitle = "미국장 마감 브리프",
            videoTitle = "${today.format(DateTimeFormatter.ofPattern("M월 d일"))} 미국장 마감 브리프",
            transcriptLength = analysis.summary.length + analysis.keyPoints.sumOf { it.length },
            keyTickers = keyTickers,
            source = MediaSource.EVENING_BRIEF,
            analysis = analysis,
        )
        mediaSummaryRepository.save(summary)
        log.info("EveningBrief MediaSummary saved. videoId={}, keyTickers={}", videoId, keyTickers.size)
    }
}
