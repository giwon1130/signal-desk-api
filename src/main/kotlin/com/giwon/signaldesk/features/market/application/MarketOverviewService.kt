package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

@Service
class MarketOverviewService(
    private val krxOfficialClient: KrxOfficialClient,
    private val cboeVixClient: CboeVixClient,
    private val usIndexService: UsIndexService,
    private val naverGlobalQuoteClient: NaverGlobalQuoteClient,
    private val yahooFinanceScreenerClient: YahooFinanceScreenerClient,
    private val googleNewsRssClient: GoogleNewsRssClient,
    private val marketSessionService: MarketSessionService,
    private val alternativeSignalService: AlternativeSignalService,
    private val watchAlertService: WatchAlertService,
    private val compositeRiskService: CompositeRiskService,
    private val dailyBriefBuilder: DailyBriefBuilder,
    private val personalContextAnnotator: PersonalContextAnnotator,
    private val recommendationMetricsCalculator: RecommendationMetricsCalculator,
    private val pickNewsMatcher: PickNewsMatcher,
    private val enrichmentService: WorkspaceEnrichmentService,
) {
    private val logger = LoggerFactory.getLogger(MarketOverviewService::class.java)

    @Volatile private var cachedCore: CachedMarketCore? = null
    @Volatile private var cachedNews: CachedNewsSection? = null

    // 휴장 중엔 데이터가 거의 안 바뀌니 cache TTL 연장 — 외부 API 호출 / Railway compute 절감.
    private val coreTtlOpen: Duration   = Duration.ofSeconds(60)
    private val coreTtlClosed: Duration = Duration.ofMinutes(3)
    private val newsTtlOpen: Duration   = Duration.ofMinutes(5)
    private val newsTtlClosed: Duration = Duration.ofMinutes(15)

    private fun coreTtl(): Duration {
        val cached = cachedCore ?: return coreTtlOpen
        val anyOpen = cached.marketSessions.any { it.isOpen }
        return if (anyOpen) coreTtlOpen else coreTtlClosed
    }
    private fun newsTtl(): Duration {
        val cached = cachedCore
        val anyOpen = cached?.marketSessions?.any { it.isOpen } == true
        return if (anyOpen) newsTtlOpen else newsTtlClosed
    }

    fun getSummary(userId: UUID? = null): MarketSummaryResponse {
        val core = getCoreSnapshot()
        val quotes = enrichmentService.loadKoreanQuotes(userId)
        val snapshot = enrichmentService.buildWorkspaceSnapshot(quotes, userId)
        val annotatedAi = personalContextAnnotator.annotateRecommendations(
            snapshot.aiRecommendations, snapshot.watchlist, snapshot.portfolio,
        )
        val summaryNews = getCachedNews().news
        val aiRecommendations = annotatedAi.copy(
            picks = attachNewsLinks(annotatedAi.picks, summaryNews),
            executionLogs = attachNewsLinksToLogs(annotatedAi.executionLogs, summaryNews),
            metrics = recommendationMetricsCalculator.compute(annotatedAi.trackRecords),
        )
        val alternativeSignals = personalContextAnnotator.annotateAlternativeSignals(
            core.alternativeSignals, snapshot.watchlist, snapshot.portfolio,
        )
        val watchAlerts = watchAlertService.buildWatchAlerts(
            alternativeSignals, getCachedNews().news,
            snapshot.watchlist, snapshot.portfolio, aiRecommendations,
        )
        val tradingDay = MarketTradingDayBuilder.build(core.marketSessions)
        val briefing = dailyBriefBuilder.build(
            base = core.briefing,
            watchAlerts = watchAlerts,
            portfolio = snapshot.portfolio,
            aiRecommendations = aiRecommendations,
            marketSummary = core.marketSummary,
            alternativeSignals = alternativeSignals,
            tradingDay = tradingDay,
        )
        val news = getCachedNews().news
        val compositeRisk = compositeRiskService.build(
            alternativeSignals = alternativeSignals,
            vix = core.vixSnapshot,
            news = news,
            watchlist = snapshot.watchlist,
            portfolio = snapshot.portfolio,
            koreaMarket = core.koreaMarket,
        )
        val compositeRiskKr = compositeRiskService.buildKr(
            koreaMarket = core.koreaMarket,
            news = news,
            watchlist = snapshot.watchlist,
            portfolio = snapshot.portfolio,
            usdKrw = core.macroQuotes?.usdKrw,
        )
        val compositeRiskUs = compositeRiskService.buildUs(
            vix = core.vixSnapshot,
            alternativeSignals = alternativeSignals,
            news = news,
            watchlist = snapshot.watchlist,
            portfolio = snapshot.portfolio,
            us10y = core.macroQuotes?.us10y,
        )
        return MarketSummaryResponse(
            generatedAt = core.generatedAt, marketStatus = core.marketStatus, summary = core.summary,
            marketSummary = core.marketSummary, alternativeSignals = alternativeSignals,
            compositeRisk = compositeRisk,
            compositeRiskKr = compositeRiskKr,
            compositeRiskUs = compositeRiskUs,
            watchAlerts = watchAlerts, marketSessions = core.marketSessions,
            briefing = briefing, sourceNotes = core.sourceNotes,
            workspaceCounts = enrichmentService.buildWorkspaceCounts(userId),
            newsSentiments = listOf(
                NewsSentimentBuilder.build("KR", news),
                NewsSentimentBuilder.build("US", news),
            ),
            tradingDayStatus = tradingDay,
        )
    }

    fun getMarketSections(): MarketSectionsResponse {
        val core = getCoreSnapshot()
        return MarketSectionsResponse(generatedAt = core.generatedAt, koreaMarket = core.koreaMarket, usMarket = core.usMarket)
    }

    fun getWatchlist(userId: UUID? = null) = enrichmentService.getWatchlist(userId)
    fun getPortfolio(userId: UUID? = null) = enrichmentService.getPortfolio(userId)
    fun getAiRecommendations(userId: UUID? = null) = enrichmentService.getAiRecommendations(userId)

    /**
     * 코어 스냅샷 캐시 워밍 — 스케줄러가 호출. 사용자 요청이 콜드 리빌드(최대 11s)를 떠안지 않도록
     * 백그라운드에서 만료 전 미리 갱신한다. (refreshAhead=true → TTL 절반만 지나도 리빌드)
     */
    fun warmUp() {
        runCatching { getCoreSnapshot(refreshAhead = true) }
            .onFailure { logger.warn("core warm-up failed: {}", it.message) }
        runCatching { getCachedNews(refreshAhead = true) }
            .onFailure { logger.warn("news warm-up failed: {}", it.message) }
    }

    private fun getCoreSnapshot(refreshAhead: Boolean = false): CachedMarketCore {
        val effectiveTtl = if (refreshAhead) coreTtl().dividedBy(2) else coreTtl()
        val cached = cachedCore
        if (cached != null && Duration.between(cached.createdAt, Instant.now()) < effectiveTtl) return cached

        return synchronized(this) {
            val rechecked = cachedCore
            if (rechecked != null && Duration.between(rechecked.createdAt, Instant.now()) < effectiveTtl) return@synchronized rechecked

            val generatedAt = LocalDateTime.now()
            val koreaMarketFuture = CompletableFuture.supplyAsync { krxOfficialClient.loadKoreaMarketSection() ?: emptyKoreaMarket() }
            val vixFuture = CompletableFuture.supplyAsync { cboeVixClient.fetchVix() }
            val koreanQuotesFuture = CompletableFuture.supplyAsync { enrichmentService.loadKoreanQuotes() }
            val usIndicesFuture = CompletableFuture.supplyAsync { usIndexService.fetchUsIndices() }
            // 위험도용 거시 시세 — 원/달러 환율 + 미 10년물(야후 라이브)
            val macroQuotesFuture = CompletableFuture.supplyAsync { usIndexService.fetchMacroQuotes() }
            // 빅테크 6종 (NVDA/MSFT/AAPL/AMZN/TSLA/META) — 빅테크 sentiment 산출 baseline
            val usBigtechQuotesFuture = CompletableFuture.supplyAsync { naverGlobalQuoteClient.fetchUsQuotes(US_BIGTECH_TICKERS) }
            // 미국 거래량 상위 — leadingStocks 동적화용
            val usMostActivesFuture = CompletableFuture.supplyAsync { yahooFinanceScreenerClient.fetchMostActives(8) }

            // 외부 API 장애 시 fallback으로 처리 — join() 예외가 전체 엔드포인트를 crash시키지 않도록
            val koreaMarketBase = runCatching { koreaMarketFuture.join() }
                .onFailure { logger.warn("KRX market fetch failed → fallback to default. msg={}", it.message) }
                .getOrElse { emptyKoreaMarket() }
            val vixSnapshot = runCatching { vixFuture.join() }
                .onFailure { logger.warn("VIX fetch failed. msg={}", it.message) }
                .getOrNull()
            val koreanQuotes = runCatching { koreanQuotesFuture.join() }
                .onFailure { logger.warn("Naver KR quotes fetch failed. msg={}", it.message) }
                .getOrDefault(emptyMap())
            val usIndicesSnapshot = runCatching { usIndicesFuture.join() }
                .onFailure { logger.warn("US indices (FRED) fetch failed. msg={}", it.message) }
                .getOrNull()
            val macroQuotes = runCatching { macroQuotesFuture.join() }
                .onFailure { logger.warn("Macro quotes (FX/UST) fetch failed. msg={}", it.message) }
                .getOrNull()
            val usBigtechQuotes = runCatching { usBigtechQuotesFuture.join() }
                .onFailure { logger.warn("US bigtech quotes (Naver global) fetch failed. msg={}", it.message) }
                .getOrDefault(emptyMap())
            val usMostActives = runCatching { usMostActivesFuture.join() }
                .onFailure { logger.warn("US most_actives (Yahoo screener) fetch failed. msg={}", it.message) }
                .getOrDefault(emptyList())

            val koreaMarket = koreaMarketBase.copy(
                leadingStocks = enrichmentService.refreshKoreanLeadingStocks(koreaMarketBase.leadingStocks, koreanQuotes)
            )
            val marketSessions = marketSessionService.buildMarketSessions()
            val alternativeSignals = alternativeSignalService.buildAlternativeSignals()

            val snapshot = CachedMarketCore(
                createdAt = Instant.now(),
                generatedAt = generatedAt.toString(),
                marketStatus = marketSessionService.buildMarketStatus(marketSessions),
                summary = "장 시작 전 점검 → 단타 픽 확인 → 보유 모니터까지, 오늘 하루를 한 화면에서 따라가는 개인 투자 대시보드야.",
                marketSummary = listOf(
                    SummaryMetric("Fear Meter", MarketHeatCalculator.fearMeter(vixSnapshot), MarketHeatCalculator.fearMeterState(vixSnapshot), MarketHeatCalculator.fearMeterNote(vixSnapshot)),
                    SummaryMetric("KR Heat", MarketHeatCalculator.krHeat(koreaMarket), MarketHeatCalculator.krHeatState(koreaMarket), "코스피/코스닥 등락률과 거래 강도를 기준으로 계산"),
                    run { val h = MarketHeatCalculator.usHeat(vixSnapshot, usIndicesSnapshot); SummaryMetric("US Heat", h, MarketHeatCalculator.usHeatState(h), "미국 지수와 VIX를 기준으로 계산") },
                    SummaryMetric("Flow Bias", MarketHeatCalculator.flowBias(koreaMarket), MarketHeatCalculator.flowBiasState(koreaMarket), MarketHeatCalculator.flowBiasDetail(koreaMarket))
                ),
                alternativeSignals = alternativeSignals,
                vixSnapshot = vixSnapshot,
                marketSessions = marketSessions,
                koreaMarket = koreaMarket,
                usMarket = buildUsMarket(vixSnapshot, usIndicesSnapshot, usBigtechQuotes, usMostActives),
                macroQuotes = macroQuotes,
                briefing = DailyBriefing(
                    headline = "오늘은 한국은 반도체, 미국은 빅테크가 중심이고, 과열 추격보다는 눌림 확인 후 진입이 맞습니다.",
                    preMarket = listOf("한국/미국 관심 종목 각각 3개만 우선순위 설정", "KOSPI/KOSDAQ, NASDAQ/S&P 방향과 VIX 같이 확인", "외국인/기관 수급이 붙는 종목만 먼저 본다"),
                    afterMarket = listOf("오늘 AI 추천 종목이 실제로 얼마나 움직였는지 복기", "보유 종목 수익률과 수급 방향이 일치했는지 체크", "내일은 한국/미국 각 2종목만 남기고 나머지는 관심 해제")
                ),
                sourceNotes = listOf(
                    SourceNote("한국 지수/수급", "KRX 정보데이터시스템", "https://data.krx.co.kr"),
                    SourceNote("한국 종목 현재가", "Naver Finance Realtime", "https://finance.naver.com"),
                    SourceNote("미국 공포지수", "CBOE VIX", "https://www.cboe.com/tradable_products/vix/"),
                    SourceNote("한국/미국 주요 뉴스", "Google News RSS", "https://news.google.com"),
                    SourceNote("미국 지수", "FRED", "https://fred.stlouisfed.org"),
                    SourceNote("미국 종목 현재가", "Naver 해외주식", "https://m.stock.naver.com"),
                    SourceNote("실험 지표", "PizzINT", "https://www.pizzint.watch/")
                )
            )
            cachedCore = snapshot
            snapshot
        }
    }

    private fun getCachedNews(refreshAhead: Boolean = false): CachedNewsSection {
        val effectiveTtl = if (refreshAhead) newsTtl().dividedBy(2) else newsTtl()
        val cached = cachedNews
        if (cached != null && Duration.between(cached.createdAt, Instant.now()) < effectiveTtl) return cached

        return synchronized(this) {
            val rechecked = cachedNews
            if (rechecked != null && Duration.between(rechecked.createdAt, Instant.now()) < effectiveTtl) return@synchronized rechecked
            val snapshot = CachedNewsSection(
                createdAt = Instant.now(),
                generatedAt = LocalDateTime.now().toString(),
                news = googleNewsRssClient.fetchMarketNews() ?: emptyList(),
            )
            cachedNews = snapshot
            snapshot
        }
    }

    /**
     * 미국 시장 섹션. 더미값을 두지 않고 실데이터만 채운다.
     * - indices: FRED 응답이 있을 때만. 실패하면 빈 리스트 (KR 의 emptyKoreaMarket 과 동일 원칙).
     * - leadingStocks: Yahoo Finance most_actives (거래량 상위) — 동적 갱신.
     * - sentiment: VIX(실데이터) + 빅테크 6종(고정 catalog) 평균 등락 파생.
     *   leadingStocks와 분리한 이유: 빅테크 sentiment는 정의가 안정적이어야 해서 most_actives 변동에 휘둘리면 안 됨.
     * - investorFlows: 미국 투자자 수급은 무료 실데이터 소스가 없어 비운다 (가짜값 금지).
     */
    private fun buildUsMarket(
        vixSnapshot: VixSnapshot?,
        usIndicesSnapshot: UsIndicesSnapshot?,
        usBigtechQuotes: Map<String, StockQuote>,
        usMostActives: List<YahooQuote>,
    ): MarketSection {
        val indices = buildList {
            usIndicesSnapshot?.nasdaq?.let {
                add(IndexMetric("NASDAQ", it.currentValue, it.changeRate, buildIndexChartPeriods(it.currentValue, it.changeRate, it.chart)))
            }
            usIndicesSnapshot?.sp500?.let {
                add(IndexMetric("S&P 500", it.currentValue, it.changeRate, buildIndexChartPeriods(it.currentValue, it.changeRate, it.chart)))
            }
        }
        // 빅테크 sentiment 산출용 (정의 안정성)
        val bigtechSnapshots = US_BIGTECH_CATALOG.mapNotNull { entry ->
            val quote = usBigtechQuotes[entry.ticker] ?: return@mapNotNull null
            TickerSnapshot(
                ticker = entry.ticker, name = entry.name, sector = entry.sector,
                price = quote.currentPrice, changeRate = quote.changeRate,
                stance = usStance(quote.changeRate),
            )
        }
        // 화면에 노출되는 leadingStocks 는 Yahoo most_actives 동적 ranking
        val leadingStocks = usMostActives.map { q ->
            TickerSnapshot(
                ticker = q.ticker,
                name = q.name,
                sector = q.exchange.ifBlank { "US Stock" },
                price = q.price.roundToInt(),
                changeRate = q.changeRate,
                stance = usStance(q.changeRate),
            )
        }
        val sentiment = buildList {
            add(SentimentMetric("VIX 기반 공포지수", MarketHeatCalculator.vixState(vixSnapshot), MarketHeatCalculator.vixScore(vixSnapshot), MarketHeatCalculator.vixNote(vixSnapshot)))
            bigtechSentiment(bigtechSnapshots)?.let { add(it) }
        }
        return MarketSection(
            market = "US", title = "미국 시장",
            indices = indices,
            sentiment = sentiment,
            investorFlows = emptyList(),
            leadingStocks = leadingStocks,
        )
    }

    /** 등락률 기반 stance 문구 — 하드코딩 대신 실시세에서 파생. */
    private fun usStance(changeRate: Double): String = when {
        changeRate >= 2.0 -> "모멘텀 강세"
        changeRate >= 0.3 -> "추세 유지"
        changeRate <= -2.0 -> "변동성 주의"
        changeRate <= -0.3 -> "약세"
        else -> "관망"
    }

    /** 주요 빅테크 평균 등락률에서 "빅테크 선호" 심리 지표를 파생. 종목이 없으면 생략. */
    private fun bigtechSentiment(leadingStocks: List<TickerSnapshot>): SentimentMetric? {
        if (leadingStocks.isEmpty()) return null
        val avg = leadingStocks.map { it.changeRate }.average()
        val score = (50 + avg * 9).roundToInt().coerceIn(0, 100)
        val state = when {
            avg >= 1.0 -> "높음"
            avg <= -1.0 -> "약함"
            else -> "중립"
        }
        return SentimentMetric(
            "빅테크 선호", state, score,
            "주요 빅테크 ${leadingStocks.size}종목 평균 등락률 ${"%.2f".format(avg)}% 기준",
        )
    }

    private fun emptyKoreaMarket() = MarketSection(
        market = "KR", title = "한국 시장",
        indices = emptyList(),
        sentiment = emptyList(),
        investorFlows = emptyList(),
        leadingStocks = emptyList(),
    )

    // ─── News Linking ───────────────────────────────────────────────────────
    // 추천 근거를 뉴스 헤드라인으로 뒷받침: name 이 제목에 포함되는 첫 뉴스 URL 을 붙인다.
    // 매칭 실패하면 null — UI 는 링크를 감춘다.

    private fun attachNewsLinks(picks: List<RecommendationPick>, news: List<MarketNews>): List<RecommendationPick> {
        if (news.isEmpty()) return picks
        return picks.map { p ->
            val match = pickNewsMatcher.findMatch(p.market, p.name, p.ticker, news)
            if (match == null) p else p.copy(newsUrl = match.url, newsTitle = match.title)
        }
    }

    private fun attachNewsLinksToLogs(logs: List<RecommendationExecutionLog>, news: List<MarketNews>): List<RecommendationExecutionLog> {
        if (news.isEmpty()) return logs
        return logs.map { l ->
            val match = pickNewsMatcher.findMatch(l.market, l.name, l.ticker, news)
            if (match == null) l else l.copy(newsUrl = match.url, newsTitle = match.title)
        }
    }

    /** 빅테크 6종 — 빅테크 sentiment 산출용 고정 catalog. leadingStocks는 Yahoo most_actives로 동적 갱신. */
    private data class UsBigtech(val ticker: String, val name: String, val sector: String)

    companion object {
        private val US_BIGTECH_CATALOG = listOf(
            UsBigtech("NVDA", "NVIDIA", "AI 반도체"),
            UsBigtech("MSFT", "Microsoft", "플랫폼"),
            UsBigtech("AAPL", "Apple", "하드웨어"),
            UsBigtech("AMZN", "Amazon", "커머스/클라우드"),
            UsBigtech("TSLA", "Tesla", "전기차"),
            UsBigtech("META", "Meta", "광고/플랫폼"),
        )
        private val US_BIGTECH_TICKERS: List<String> = US_BIGTECH_CATALOG.map { it.ticker }
    }
}
