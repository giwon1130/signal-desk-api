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

@Service
class MarketOverviewService(
    private val krxOfficialClient: KrxOfficialClient,
    private val cboeVixClient: CboeVixClient,
    private val fredIndexClient: FredIndexClient,
    private val googleNewsRssClient: GoogleNewsRssClient,
    private val marketSessionService: MarketSessionService,
    private val alternativeSignalService: AlternativeSignalService,
    private val watchAlertService: WatchAlertService,
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

    fun getOverview(userId: UUID? = null): MarketOverviewResponse {
        val core = getCoreSnapshot()
        val news = getNewsFeed().news
        val watchlist = enrichmentService.getWatchlist(userId).watchlist
        val portfolio = enrichmentService.getPortfolio(userId).portfolio
        val aiRecommendationsRaw = enrichmentService.getAiRecommendations(userId).aiRecommendations
        val paperTrading = enrichmentService.getPaperTrading(userId).paperTrading
        val annotatedAi = personalContextAnnotator.annotateRecommendations(aiRecommendationsRaw, watchlist, portfolio)
        val aiRecommendations = annotatedAi.copy(
            picks = attachNewsLinks(annotatedAi.picks, news),
            executionLogs = attachNewsLinksToLogs(annotatedAi.executionLogs, news),
            metrics = recommendationMetricsCalculator.compute(annotatedAi.trackRecords),
        )
        val alternativeSignals = personalContextAnnotator.annotateAlternativeSignals(core.alternativeSignals, watchlist, portfolio)
        val watchAlerts = watchAlertService.buildWatchAlerts(alternativeSignals, news, watchlist, portfolio, aiRecommendations)
        val tradingDay = buildTradingDayStatus(core.marketSessions)
        val briefing = dailyBriefBuilder.build(
            base = core.briefing,
            watchAlerts = watchAlerts,
            portfolio = portfolio,
            aiRecommendations = aiRecommendations,
            marketSummary = core.marketSummary,
            alternativeSignals = alternativeSignals,
            tradingDay = tradingDay,
        )
        return MarketOverviewResponse(
            generatedAt = core.generatedAt, marketStatus = core.marketStatus, summary = core.summary,
            marketSummary = core.marketSummary, alternativeSignals = alternativeSignals,
            watchAlerts = watchAlerts, marketSessions = core.marketSessions,
            koreaMarket = core.koreaMarket, usMarket = core.usMarket, news = news,
            watchlist = watchlist, portfolio = portfolio, aiRecommendations = aiRecommendations,
            paperTrading = paperTrading, briefing = briefing, sourceNotes = core.sourceNotes
        )
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
        val tradingDay = buildTradingDayStatus(core.marketSessions)
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
        return MarketSummaryResponse(
            generatedAt = core.generatedAt, marketStatus = core.marketStatus, summary = core.summary,
            marketSummary = core.marketSummary, alternativeSignals = alternativeSignals,
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

    private fun buildTradingDayStatus(sessions: List<MarketSessionStatus>): TradingDayStatus {
        val kr = sessions.firstOrNull { it.market == "KR" }
        val us = sessions.firstOrNull { it.market == "US" }
        val krOpen = kr?.isOpen == true
        val usOpen = us?.isOpen == true
        val isWeekend = (kr?.note?.contains("주말") == true) && (us?.note?.contains("주말") == true)
        val isHoliday = !isWeekend && kr?.note?.contains("휴장") == true && us?.note?.contains("휴장") == true

        val today = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
        val nextKrOpen = nextKoreanOpen(today)
        val nextLabel = "${koreanDayLabel(nextKrOpen.dayOfWeek)} ${nextKrOpen.toLocalDate()} 09:00 KST"

        val (headline, advice) = when {
            krOpen || usOpen -> "장이 열려 있어 — 평소처럼 진행" to "오늘의 단타 픽 / 보유 모니터를 그대로 사용해도 OK"
            isWeekend -> "주말 휴장 — 다음 거래일 준비 모드" to "신규 진입은 다음 개장 후. 오늘은 관심종목 정리, AI 로그 복기, 손절·익절 라인 재설정만 해."
            isHoliday -> "오늘은 휴장일 — 시장 재개 전 정리" to "체결은 안 되니까 시나리오만 점검하고 다음 거래일 준비."
            else -> "정규장 종료 — 시간외/다음날 준비" to "오늘 마감 결과를 보고 내일 진입 후보 1~2개만 추려두자."
        }

        return TradingDayStatus(
            krOpen = krOpen, usOpen = usOpen,
            isWeekend = isWeekend, isHoliday = isHoliday,
            headline = headline, nextTradingDay = nextLabel, advice = advice,
        )
    }

    private fun nextKoreanOpen(now: java.time.ZonedDateTime): java.time.ZonedDateTime {
        var candidate = now.toLocalDate()
        if (now.toLocalTime() >= java.time.LocalTime.of(9, 0)) candidate = candidate.plusDays(1)
        while (candidate.dayOfWeek == java.time.DayOfWeek.SATURDAY || candidate.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1)
        }
        return candidate.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Seoul"))
    }

    private fun koreanDayLabel(day: java.time.DayOfWeek): String = when (day) {
        java.time.DayOfWeek.MONDAY -> "월요일"
        java.time.DayOfWeek.TUESDAY -> "화요일"
        java.time.DayOfWeek.WEDNESDAY -> "수요일"
        java.time.DayOfWeek.THURSDAY -> "목요일"
        java.time.DayOfWeek.FRIDAY -> "금요일"
        java.time.DayOfWeek.SATURDAY -> "토요일"
        java.time.DayOfWeek.SUNDAY -> "일요일"
    }

    fun getMarketSections(): MarketSectionsResponse {
        val core = getCoreSnapshot()
        return MarketSectionsResponse(generatedAt = core.generatedAt, koreaMarket = core.koreaMarket, usMarket = core.usMarket)
    }

    fun getNewsFeed(): NewsFeedResponse {
        val news = getCachedNews()
        return NewsFeedResponse(generatedAt = news.generatedAt, news = news.news)
    }

    fun getWatchlist(userId: UUID? = null) = enrichmentService.getWatchlist(userId)
    fun getPortfolio(userId: UUID? = null) = enrichmentService.getPortfolio(userId)
    fun getAiRecommendations(userId: UUID? = null) = enrichmentService.getAiRecommendations(userId)
    fun getPaperTrading(userId: UUID? = null) = enrichmentService.getPaperTrading(userId)

    private fun getCoreSnapshot(): CachedMarketCore {
        val cached = cachedCore
        if (cached != null && Duration.between(cached.createdAt, Instant.now()) < coreTtl()) return cached

        return synchronized(this) {
            val rechecked = cachedCore
            if (rechecked != null && Duration.between(rechecked.createdAt, Instant.now()) < coreTtl()) return@synchronized rechecked

            val generatedAt = LocalDateTime.now()
            val koreaMarketFuture = CompletableFuture.supplyAsync { krxOfficialClient.loadKoreaMarketSection() ?: defaultKoreaMarket() }
            val vixFuture = CompletableFuture.supplyAsync { cboeVixClient.fetchVix() }
            val koreanQuotesFuture = CompletableFuture.supplyAsync { enrichmentService.loadKoreanQuotes() }
            val usIndicesFuture = CompletableFuture.supplyAsync { fredIndexClient.fetchUsIndices() }

            // 외부 API 장애 시 fallback으로 처리 — join() 예외가 전체 엔드포인트를 crash시키지 않도록
            val koreaMarketBase = runCatching { koreaMarketFuture.join() }
                .onFailure { logger.warn("KRX market fetch failed → fallback to default. msg={}", it.message) }
                .getOrElse { defaultKoreaMarket() }
            val vixSnapshot = runCatching { vixFuture.join() }
                .onFailure { logger.warn("VIX fetch failed. msg={}", it.message) }
                .getOrNull()
            val koreanQuotes = runCatching { koreanQuotesFuture.join() }
                .onFailure { logger.warn("Naver KR quotes fetch failed. msg={}", it.message) }
                .getOrDefault(emptyMap())
            val usIndicesSnapshot = runCatching { usIndicesFuture.join() }
                .onFailure { logger.warn("US indices (FRED) fetch failed. msg={}", it.message) }
                .getOrNull()

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
                    SummaryMetric("Fear Meter", calculateFearMeter(vixSnapshot), buildFearMeterState(vixSnapshot), buildFearMeterNote(vixSnapshot)),
                    SummaryMetric("KR Heat", calculateKrHeat(koreaMarket), buildKrHeatState(koreaMarket), "코스피/코스닥 등락률과 거래 강도를 기준으로 계산"),
                    run { val h = calculateUsHeat(vixSnapshot, usIndicesSnapshot); SummaryMetric("US Heat", h, buildUsHeatState(h), "미국 지수와 VIX를 기준으로 계산") },
                    SummaryMetric("Flow Bias", calculateFlowBias(koreaMarket), buildFlowBiasState(koreaMarket), "국내는 KRX 수급, 미국은 지수/변동성 기준")
                ),
                alternativeSignals = alternativeSignals,
                marketSessions = marketSessions,
                koreaMarket = koreaMarket,
                usMarket = buildUsMarket(vixSnapshot, usIndicesSnapshot),
                briefing = DailyBriefing(
                    headline = "오늘은 한국은 반도체, 미국은 빅테크가 중심이고, 과열 추격보다는 눌림 확인 후 진입이 맞아.",
                    preMarket = listOf("한국/미국 관심 종목 각각 3개만 우선순위 설정", "KOSPI/KOSDAQ, NASDAQ/S&P 방향과 VIX 같이 확인", "외국인/기관 수급이 붙는 종목만 먼저 본다"),
                    afterMarket = listOf("오늘 AI 추천 종목이 실제로 얼마나 움직였는지 복기", "보유 종목 수익률과 수급 방향이 일치했는지 체크", "내일은 한국/미국 각 2종목만 남기고 나머지는 관심 해제")
                ),
                sourceNotes = listOf(
                    SourceNote("한국 지수/수급", "KRX 정보데이터시스템", "https://data.krx.co.kr"),
                    SourceNote("한국 종목 현재가", "Naver Finance Realtime", "https://finance.naver.com"),
                    SourceNote("미국 공포지수", "CBOE VIX", "https://www.cboe.com/tradable_products/vix/"),
                    SourceNote("한국/미국 주요 뉴스", "Google News RSS", "https://news.google.com"),
                    SourceNote("미국 지수", "FRED", "https://fred.stlouisfed.org"),
                    SourceNote("미국 지연 시세 확장 후보", "Alpha Vantage", "https://www.alphavantage.co/documentation/"),
                    SourceNote("실험 지표", "PizzINT", "https://www.pizzint.watch/")
                )
            )
            cachedCore = snapshot
            snapshot
        }
    }

    private fun getCachedNews(): CachedNewsSection {
        val cached = cachedNews
        if (cached != null && Duration.between(cached.createdAt, Instant.now()) < newsTtl()) return cached

        return synchronized(this) {
            val rechecked = cachedNews
            if (rechecked != null && Duration.between(rechecked.createdAt, Instant.now()) < newsTtl()) return@synchronized rechecked
            val snapshot = CachedNewsSection(
                createdAt = Instant.now(),
                generatedAt = LocalDateTime.now().toString(),
                news = googleNewsRssClient.fetchMarketNews() ?: defaultNews(),
            )
            cachedNews = snapshot
            snapshot
        }
    }

    private fun buildUsMarket(vixSnapshot: VixSnapshot?, usIndicesSnapshot: UsIndicesSnapshot?): MarketSection {
        val nasdaq = usIndicesSnapshot?.nasdaq
        val sp500 = usIndicesSnapshot?.sp500
        return MarketSection(
            market = "US", title = "미국 시장",
            indices = listOf(
                IndexMetric("NASDAQ", nasdaq?.currentValue ?: 18342.40, nasdaq?.changeRate ?: 1.12,
                    buildIndexChartPeriods(nasdaq?.currentValue ?: 18342.40, nasdaq?.changeRate ?: 1.12,
                        nasdaq?.chart ?: listOf(17840.0, 17910.0, 18040.0, 18160.0, 18250.0, 18342.0))),
                IndexMetric("S&P 500", sp500?.currentValue ?: 5224.60, sp500?.changeRate ?: 0.74,
                    buildIndexChartPeriods(sp500?.currentValue ?: 5224.60, sp500?.changeRate ?: 0.74,
                        sp500?.chart ?: listOf(5110.0, 5140.0, 5168.0, 5181.0, 5202.0, 5224.0))),
            ),
            sentiment = listOf(
                SentimentMetric("VIX 기반 공포지수", buildVixState(vixSnapshot), calculateVixScore(vixSnapshot), buildVixNote(vixSnapshot)),
                SentimentMetric("빅테크 선호", "높음", 72, "AI/반도체 종목으로 자금 집중"),
                SentimentMetric("과열 경계", "중간", 49, "실적 시즌 전후로 단기 변동성 확대 가능")
            ),
            investorFlows = listOf(
                InvestorFlow("기관", 3180.0, "나스닥 대형주 유입", true),
                InvestorFlow("리테일", 1240.0, "테마 추종 매수", true),
                InvestorFlow("헤지성 자금", -860.0, "이익실현·헤지 혼재", false)
            ),
            leadingStocks = listOf(
                TickerSnapshot("NVDA", "NVIDIA", "AI 반도체", 945, 2.84, "모멘텀 강세"),
                TickerSnapshot("MSFT", "Microsoft", "플랫폼", 428, 0.91, "추세 유지"),
                TickerSnapshot("AAPL", "Apple", "하드웨어", 188, -0.34, "관망"),
                TickerSnapshot("AMZN", "Amazon", "커머스/클라우드", 184, 1.08, "양호"),
                TickerSnapshot("TSLA", "Tesla", "전기차", 171, -1.92, "변동성 주의"),
                TickerSnapshot("META", "Meta", "광고/플랫폼", 503, 1.31, "실적 기대")
            )
        )
    }

    private fun defaultKoreaMarket() = MarketSection(
        market = "KR", title = "한국 시장",
        indices = listOf(
            IndexMetric("KOSPI", 2748.32, 0.82, buildIndexChartPeriods(2748.32, 0.82, listOf(2660.0, 2688.0, 2704.0, 2719.0, 2733.0, 2748.0))),
            IndexMetric("KOSDAQ", 882.51, -0.24, buildIndexChartPeriods(882.51, -0.24, listOf(901.0, 896.0, 891.0, 888.0, 885.0, 882.0)))
        ),
        sentiment = listOf(
            SentimentMetric("변동성", "보통", 54, "공포 구간은 아니지만 코스닥은 흔들림이 남아 있음"),
            SentimentMetric("매수심리", "강세 우위", 61, "외국인/개인 동시 유입 종목이 늘어남"),
            SentimentMetric("추격위험", "중간", 47, "장초반 급등 추격은 여전히 부담")
        ),
        investorFlows = listOf(
            InvestorFlow("개인", 1824.0, "중소형주 중심 매수", true),
            InvestorFlow("외국인", 2431.0, "반도체/금융 대형주 유입", true),
            InvestorFlow("기관", -1168.0, "차익실현 우위", false)
        ),
        leadingStocks = listOf(
            TickerSnapshot("005930", "삼성전자", "반도체", 84200, 1.44, "관심 유지"),
            TickerSnapshot("000660", "SK하이닉스", "반도체", 201500, 2.11, "강한 흐름"),
            TickerSnapshot("035420", "NAVER", "플랫폼", 184300, -0.62, "눌림 체크"),
            TickerSnapshot("068270", "셀트리온", "바이오", 176200, -1.15, "주의"),
            TickerSnapshot("005380", "현대차", "자동차", 248500, 0.93, "추세 유지"),
            TickerSnapshot("105560", "KB금융", "금융", 78100, 1.21, "수급 양호")
        )
    )

    private fun defaultNews() = listOf(
        MarketNews("KR", "반도체 수출 기대감 유지, 외국인 자금 유입 확대", "매일경제", "https://www.mk.co.kr", "반도체 대형주 강세에 우호적"),
        MarketNews("KR", "코스닥 바이오 변동성 확대, 단기 수급 주의", "한국경제", "https://www.hankyung.com", "바이오 섹터는 눌림 확인 필요"),
        MarketNews("US", "미국 빅테크 실적 기대감에 나스닥 강세", "Reuters", "https://www.reuters.com", "AI 대형주 모멘텀 지속"),
        MarketNews("US", "연준 발언 앞두고 금리 민감주 혼조", "Bloomberg", "https://www.bloomberg.com", "금리 민감 업종은 변동성 대비 필요")
    )

    // ─── Market Summary Calculations ─────────────────────────────────────────

    private fun calculateFearMeter(vixSnapshot: VixSnapshot?) =
        (100 - ((vixSnapshot?.currentPrice ?: 17.0) - 12) * 4).coerceIn(0.0, 100.0)

    private fun buildFearMeterState(vixSnapshot: VixSnapshot?): String {
        val vix = vixSnapshot?.currentPrice ?: return "중립"
        return when { vix < 15 -> "낙관 우위"; vix < 20 -> "위험선호 우위"; vix < 26 -> "경계 구간"; else -> "공포 확대" }
    }

    private fun buildFearMeterNote(vixSnapshot: VixSnapshot?): String {
        val vix = vixSnapshot?.currentPrice ?: return "VIX 실데이터 미연결 상태라 기본 공포 점수를 사용 중"
        return "공식 CBOE VIX ${"%.2f".format(vix)} 기준으로 계산한 미국 시장 위험심리"
    }

    private fun calculateVixScore(vixSnapshot: VixSnapshot?) = calculateFearMeter(vixSnapshot).toInt()

    private fun buildVixState(vixSnapshot: VixSnapshot?) = when (buildFearMeterState(vixSnapshot)) {
        "낙관 우위", "위험선호 우위" -> "낮음"; "경계 구간" -> "중간"; else -> "높음"
    }

    private fun buildVixNote(vixSnapshot: VixSnapshot?): String {
        val vix = vixSnapshot?.currentPrice ?: return "VIX 기본값 기반"
        val change = vixSnapshot.priceChange
        val signedChange = if (change > 0) "+${"%.2f".format(change)}" else "%.2f".format(change)
        return "CBOE VIX ${"%.2f".format(vix)} (${signedChange}) 기준 위험심리"
    }

    private fun calculateUsHeat(vixSnapshot: VixSnapshot?, usIndicesSnapshot: UsIndicesSnapshot?): Double {
        val nasdaq = usIndicesSnapshot?.nasdaq
        val sp500 = usIndicesSnapshot?.sp500
        val avgChange = listOfNotNull(nasdaq?.changeRate, sp500?.changeRate)
            .average().takeIf { it.isFinite() } ?: 0.0
        val vix = vixSnapshot?.currentPrice ?: 20.0
        return (50 + avgChange * 6 - (vix - 20) * 1.5).coerceIn(0.0, 100.0)
    }

    private fun buildUsHeatState(usHeat: Double): String = when {
        usHeat >= 65 -> "AI/빅테크 중심"
        usHeat >= 50 -> "강세 우위"
        usHeat >= 35 -> "혼조"
        else -> "약세 우위"
    }

    private fun calculateKrHeat(koreaMarket: MarketSection) =
        (50 + koreaMarket.indices.map { it.changeRate }.average() * 8).coerceIn(0.0, 100.0)

    private fun buildKrHeatState(koreaMarket: MarketSection): String {
        val kospiChange = koreaMarket.indices.firstOrNull { it.label == "KOSPI" }?.changeRate ?: 0.0
        val kosdaqChange = koreaMarket.indices.firstOrNull { it.label == "KOSDAQ" }?.changeRate ?: 0.0
        return when {
            kospiChange > kosdaqChange && kospiChange >= 0 -> "코스피 강세"
            kosdaqChange > kospiChange && kosdaqChange >= 0 -> "코스닥 강세"
            else -> "동반 약세"
        }
    }

    private fun calculateFlowBias(koreaMarket: MarketSection): Double {
        val foreignFlow = koreaMarket.investorFlows.firstOrNull { it.investor == "외국인" }?.amountBillionWon ?: 0.0
        val institutionFlow = koreaMarket.investorFlows.firstOrNull { it.investor == "기관" }?.amountBillionWon ?: 0.0
        return (50 + ((foreignFlow + institutionFlow) / 60)).coerceIn(0.0, 100.0)
    }

    private fun buildFlowBiasState(koreaMarket: MarketSection): String {
        val topFlow = koreaMarket.investorFlows.maxByOrNull { kotlin.math.abs(it.amountBillionWon) }
        return topFlow?.let { "${it.investor} ${if (it.positive) "우위" else "매도 우위"}" } ?: "수급 중립"
    }

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
}
