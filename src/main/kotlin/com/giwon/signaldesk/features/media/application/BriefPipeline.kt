package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.events.application.FinnhubClient
import com.giwon.signaldesk.features.events.application.MarketEvent
import com.giwon.signaldesk.features.events.application.MarketEventService
import com.giwon.signaldesk.features.market.application.CboeVixClient
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.GlobalIndex
import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.InvestorFlowSnapshot
import com.giwon.signaldesk.features.market.application.KrxOfficialClient
import com.giwon.signaldesk.features.market.application.MacroSnapshot
import com.giwon.signaldesk.features.market.application.MarketNews
import com.giwon.signaldesk.features.market.application.MarketSection
import com.giwon.signaldesk.features.market.application.NaverInvestorRankClient
import com.giwon.signaldesk.features.market.application.TopMover
import com.giwon.signaldesk.features.market.application.TopMoversService
import com.giwon.signaldesk.features.market.application.UsIndexService
import com.giwon.signaldesk.features.market.application.UsIndicesSnapshot
import com.giwon.signaldesk.features.market.application.VixSnapshot
import com.giwon.signaldesk.features.market.application.YahooQuoteClient
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushDevice
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * 브리프 공통 파이프라인 — Morning/Intraday(전체)·Evening(일부 단계) 가 공유하는 골격.
 *
 * 공유 단계:
 *  1) Gemini 활성화 게이트 + videoId("<prefix>-yyyy-MM-dd") 중복 체크
 *  2) KR 시장 외부 API 병렬 수집 (VIX/미지수/매크로/뉴스/이벤트/수급/KRX/급등락/글로벌 [+실적])
 *  3) Gemini 호출 래핑 (runCatching + 실패/빈 응답 경고 로그)
 *  4) MediaSummary 조립·저장
 *  5) 사용자 × 기기 푸시 일괄 발송 (Expo 배치 전송)
 *
 * 프롬프트(분석 호출)와 슬롯별 메타데이터·푸시 대상 선정은 각 서비스가 주입한다 — 통합하지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class BriefPipeline(
    private val vixClient: CboeVixClient,
    private val fredIndexClient: FredIndexClient,
    private val usIndexService: UsIndexService,
    private val newsRssClient: GoogleNewsRssClient,
    private val investorRankClient: NaverInvestorRankClient,
    private val krxOfficialClient: KrxOfficialClient,
    private val topMoversService: TopMoversService,
    private val yahooQuoteClient: YahooQuoteClient,
    private val finnhubClient: FinnhubClient,
    private val marketEventService: MarketEventService,
    private val geminiClient: GeminiClient,
    private val repository: MediaSummaryRepository,
    private val expoPushClient: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val titleFmt = DateTimeFormatter.ofPattern("M월 d일")

    /** 슬롯별 메타데이터 — videoId prefix·채널·소스만 다르고 파이프라인 골격은 동일. */
    data class SlotConfig(
        val logLabel: String,       // 로그 prefix — 기존 로그 문구 보존용 (예: "MorningBrief", "IntradayBrief(CLOSE)")
        val videoPrefix: String,    // videoId = "<prefix>-yyyy-MM-dd"
        val channelId: String,
        val channelTitle: String,
        val titleSuffix: String,    // 제목 = "M월 d일 <suffix>"
        val source: MediaSource,
    )

    /** KR 브리프 공통 외부 데이터 묶음 (병렬 수집 + join 완료 상태). */
    data class KrMarketData(
        val vix: VixSnapshot?,
        val indices: UsIndicesSnapshot?,
        val macro: MacroSnapshot?,
        val headlines: List<MarketNews>,
        val upcomingEvents: List<MarketEvent>,
        val investorFlow: InvestorFlowSnapshot?,
        val krMarket: MarketSection?,
        val krGainers: List<TopMover>,
        val krLosers: List<TopMover>,
        val global: List<GlobalIndex>,
        val earningsSymbols: List<String>,
    )

    /**
     * 공통 파이프라인 실행 — 게이트/중복 체크 → prepare(서비스별 사전 단계) → 병렬 수집 →
     * Gemini 분석 → MediaSummary 저장 → 푸시. 분석 실패/중복 시 null.
     *
     * @param prepare 수집 전 서비스별 컨텍스트 로드 (모닝 브리프의 보유 공시 매칭 등). 게이트 통과 후에만 실행.
     * @param dispatchPush 저장 성공 후 푸시 발송 — 예외 처리 정책(전파 vs 흡수)은 호출부가 결정.
     */
    fun <C> run(
        config: SlotConfig,
        today: LocalDate,
        force: Boolean,
        includeEarnings: Boolean = false,
        prepare: () -> C,
        disclosureCount: (C) -> Int = { 0 },
        analyze: (C, KrMarketData) -> MarketInsightAnalysis?,
        transcriptLength: (C, KrMarketData) -> Int,
        keyTickers: (C) -> List<String> = { emptyList() },
        dispatchPush: (C, MarketInsightAnalysis) -> Unit,
    ): MediaSummary? {
        if (!geminiClient.isEnabled()) {
            log.warn("{} skipped — GEMINI_API_KEY 미설정", config.logLabel)
            return null
        }
        val videoId = "${config.videoPrefix}-${today.format(dateFmt)}"
        if (!force && repository.findByVideoId(videoId) != null) {
            log.info("{} already exists. videoId={}", config.logLabel, videoId)
            return null
        }

        val context = prepare()
        val data = collectKrMarketData(today, includeEarnings)

        // 운영 관측 — 어떤 데이터 소스가 채워졌는지 한 줄로. 브리프 품질 저하 진단용.
        log.info(
            "{} sources: vix={}, usIdx={}, macro={}, krMarket={}, krMovers={}+{}, global={}, earnings={}, flow={}, headlines={}, events={}, disclosures={}",
            config.logLabel, data.vix != null, data.indices != null, data.macro != null, data.krMarket != null,
            data.krGainers.size, data.krLosers.size, data.global.size, data.earningsSymbols.size,
            data.investorFlow != null, data.headlines.size, data.upcomingEvents.size, disclosureCount(context),
        )

        val analysis = runCatching { analyze(context, data) }
            .getOrElse {
                log.warn("{} Gemini call failed", config.logLabel, it)
                null
            } ?: run {
            log.warn("{} Gemini returned null", config.logLabel)
            return null
        }

        val saved = repository.save(
            buildSummary(
                videoId = videoId,
                channelId = config.channelId,
                channelTitle = config.channelTitle,
                videoTitle = "${today.format(titleFmt)} ${config.titleSuffix}",
                transcriptLength = transcriptLength(context, data),
                keyTickers = keyTickers(context),
                source = config.source,
                analysis = analysis,
            ),
        )
        log.info("{} saved. videoId={}, headline={}", config.logLabel, videoId, analysis.headline)

        dispatchPush(context, analysis)
        return saved
    }

    /** KR 브리프 공통 외부 API 병렬 수집 — 순차 호출 시 수십 초가 걸려 Gemini 타임아웃 위험. */
    private fun collectKrMarketData(today: LocalDate, includeEarnings: Boolean): KrMarketData {
        val vixF = supplyAsync { vixClient.fetchVix() }
        val indicesF = supplyAsync { usIndexService.fetchUsIndices() }
        val macroF = supplyAsync { fredIndexClient.fetchMacro() }
        val headlinesF = supplyAsync { newsRssClient.fetchMarketNews() }
        val eventsF = supplyAsync { marketEventService.upcoming(3) }
        val flowF = supplyAsync { investorRankClient.fetchFlowSnapshot(limit = 7) }
        val krMarketF = supplyAsync { krxOfficialClient.loadKoreaMarketSection() }
        val krMoversF = supplyAsync { topMoversService.fetchTopMovers(5) }
        val globalF = supplyAsync { yahooQuoteClient.fetchIndices(YahooQuoteClient.GLOBAL_INDICES) }
        val earningsF = if (includeEarnings) {
            supplyAsync { finnhubClient.fetchEarningsCalendar(today.toString(), today.toString()) }
        } else null

        val krMovers = krMoversF.join()
        // 야후 실패로 FRED 폴백(stale)된 미국 지수는 한 세션 지연이라 방향이 반대로 찍힐 수 있음 →
        // 잘못된 방향을 넣느니 지수를 빼고(=데이터 없음 취급) 작성. 모닝·장중 브리프 공통.
        val usIndices = indicesF.join()?.let {
            if (it.stale) {
                log.warn("Brief — 미국 지수 stale(FRED 폴백) → 지수 방향 표기 생략(뉴스 중심)")
                null
            } else it
        }
        return KrMarketData(
            vix = vixF.join(),
            indices = usIndices,
            macro = macroF.join(),
            headlines = headlinesF.join() ?: emptyList(),
            upcomingEvents = eventsF.join() ?: emptyList(),
            investorFlow = flowF.join(),
            krMarket = krMarketF.join(),
            krGainers = krMovers?.let { (it.kospi.gainers + it.kosdaq.gainers).sortedByDescending { m -> m.changeRate }.take(5) } ?: emptyList(),
            krLosers = krMovers?.let { (it.kospi.losers + it.kosdaq.losers).sortedBy { m -> m.changeRate }.take(5) } ?: emptyList(),
            global = globalF.join() ?: emptyList(),
            earningsSymbols = earningsF?.join()?.map { it.symbol }?.distinct() ?: emptyList(),
        )
    }

    /** 공통 MediaSummary 조립 — id/시각/본문(headline+summary, keyPoints 불릿) 형식은 모든 브리프 동일. */
    fun buildSummary(
        videoId: String,
        channelId: String,
        channelTitle: String,
        videoTitle: String,
        transcriptLength: Int,
        keyTickers: List<String>,
        source: MediaSource,
        analysis: MarketInsightAnalysis,
    ): MediaSummary = MediaSummary(
        id = UUID.randomUUID().toString(),
        channelId = channelId,
        channelTitle = channelTitle,
        videoId = videoId,
        videoTitle = videoTitle,
        videoUrl = "",
        publishedAt = Instant.now(),
        transcriptLength = transcriptLength,
        summary = analysis.headline + "\n\n" + analysis.summary,
        flowAnalysis = analysis.keyPoints.joinToString("\n") { "• $it" },
        keyTickers = keyTickers,
        sentiment = analysis.sentiment,
        hasTranscript = true,
        source = source,
        createdAt = Instant.now(),
    )

    /** sentiment → 푸시 타이틀 이모지. */
    fun sentimentEmoji(sentiment: MediaSentiment): String = when (sentiment) {
        MediaSentiment.BULLISH -> "🟢"
        MediaSentiment.BEARISH -> "🔴"
        MediaSentiment.NEUTRAL -> "🟡"
    }

    /** 공통 푸시 (title, body) — headline 비면 fallbackTitle, body 는 180자 제한. */
    fun briefPushContent(analysis: MarketInsightAnalysis, fallbackTitle: String): Pair<String, String> {
        val title = "${sentimentEmoji(analysis.sentiment)} ${analysis.headline.ifBlank { fallbackTitle }}"
        return title to analysis.summary.take(180)
    }

    /**
     * 타깃 사용자 × 기기 메시지를 모두 모아 한 번에 발송 — Expo Push API 배치 전송.
     * @param content userId 별 (title, body) — 개인화 필요 없으면 동일 값 반환.
     * @return 발송 메시지 수 (호출부 로그용).
     */
    fun sendToTargets(
        targets: Map<UUID, List<PushDevice>>,
        pushType: String,
        content: (UUID) -> Pair<String, String>,
    ): Int {
        val messages = targets.flatMap { (userId, devices) ->
            val (title, body) = content(userId)
            devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf("type" to pushType),
                    userId = userId,
                )
            }
        }
        expoPushClient.send(messages)
        return messages.size
    }

    /** 외부 API 호출 실패는 null 로 흡수 — 데이터 소스 일부가 죽어도 브리프는 만든다. */
    fun <T> supplyAsync(block: () -> T?): CompletableFuture<T?> =
        CompletableFuture.supplyAsync { runCatching(block).getOrNull() }
}
