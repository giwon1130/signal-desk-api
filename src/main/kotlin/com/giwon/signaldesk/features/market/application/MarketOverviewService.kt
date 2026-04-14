package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.Month
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CompletableFuture

@Service
class MarketOverviewService(
    private val krxOfficialClient: KrxOfficialClient,
    private val naverFinanceQuoteClient: NaverFinanceQuoteClient,
    private val cboeVixClient: CboeVixClient,
    private val fredIndexClient: FredIndexClient,
    private val googleNewsRssClient: GoogleNewsRssClient,
    private val pizzIntClient: PizzIntClient,
    private val workspaceStore: SignalDeskWorkspaceRepository,
) {
    private val trackedBarVenues = listOf(
        TrackedVenueProxy("Freddie's Beach Bar", "Pentagon", 1.4),
        TrackedVenueProxy("The Little Gay Pub", "White House", 1.1),
    )

    @Volatile
    private var cachedCore: CachedMarketCore? = null

    @Volatile
    private var cachedNews: CachedNewsSection? = null

    private val coreTtl: Duration = Duration.ofSeconds(60)
    private val newsTtl: Duration = Duration.ofMinutes(5)

    fun getOverview(): MarketOverviewResponse {
        val core = getCoreSnapshot()
        val news = getNewsFeed().news
        val watchlist = getWatchlist().watchlist
        val portfolio = getPortfolio().portfolio
        val aiRecommendations = getAiRecommendations().aiRecommendations
        val paperTrading = getPaperTrading().paperTrading

        return MarketOverviewResponse(
            generatedAt = core.generatedAt,
            marketStatus = core.marketStatus,
            summary = core.summary,
            marketSummary = core.marketSummary,
            alternativeSignals = core.alternativeSignals,
            marketSessions = core.marketSessions,
            koreaMarket = core.koreaMarket,
            usMarket = core.usMarket,
            news = news,
            watchlist = watchlist,
            portfolio = portfolio,
            aiRecommendations = aiRecommendations,
            paperTrading = paperTrading,
            briefing = core.briefing,
            sourceNotes = core.sourceNotes
        )
    }

    fun getSummary(): MarketSummaryResponse {
        val core = getCoreSnapshot()
        return MarketSummaryResponse(
            generatedAt = core.generatedAt,
            marketStatus = core.marketStatus,
            summary = core.summary,
            marketSummary = core.marketSummary,
            alternativeSignals = core.alternativeSignals,
            marketSessions = core.marketSessions,
            briefing = core.briefing,
            sourceNotes = core.sourceNotes,
            workspaceCounts = buildWorkspaceCounts(),
        )
    }

    fun getMarketSections(): MarketSectionsResponse {
        val core = getCoreSnapshot()
        return MarketSectionsResponse(
            generatedAt = core.generatedAt,
            koreaMarket = core.koreaMarket,
            usMarket = core.usMarket,
        )
    }

    fun getNewsFeed(): NewsFeedResponse {
        val news = getCachedNews()
        return NewsFeedResponse(
            generatedAt = news.generatedAt,
            news = news.news,
        )
    }

    fun getWatchlist(): WatchlistResponse {
        val quotes = loadKoreanQuotes()
        val workspaceWatchlist = workspaceStore.loadWatchlist()
        val watchlist = refreshWatchlist(
            baseWatchlist() + workspaceWatchlist.map { item ->
                WatchItem(
                    id = item.id,
                    market = item.market,
                    ticker = item.ticker,
                    name = item.name,
                    price = item.price,
                    changeRate = item.changeRate,
                    sector = item.sector,
                    stance = item.stance,
                    note = item.note,
                    source = "USER",
                )
            },
            quotes
        )
        return WatchlistResponse(LocalDateTime.now().toString(), watchlist)
    }

    fun getPortfolio(): PortfolioResponse {
        val quotes = loadKoreanQuotes()
        val workspacePortfolioPositions = workspaceStore.loadPortfolioPositions()
        val portfolio = refreshPortfolio(
            mergePortfolio(
                basePortfolio(),
                workspacePortfolioPositions.map { position ->
                    HoldingPosition(
                        id = position.id,
                        market = position.market,
                        ticker = position.ticker,
                        name = position.name,
                        buyPrice = position.buyPrice,
                        currentPrice = position.currentPrice,
                        quantity = position.quantity,
                        profitAmount = position.profitAmount,
                        evaluationAmount = position.evaluationAmount,
                        profitRate = position.profitRate,
                        source = "USER",
                    )
                }
            ),
            quotes
        )
        return PortfolioResponse(LocalDateTime.now().toString(), portfolio)
    }

    fun getAiRecommendations(): AiRecommendationsResponse {
        val quotes = loadKoreanQuotes()
        val aiRecommendations = refreshAiRecommendations(
            mergeAiRecommendations(
                baseAiRecommendations(),
                workspaceStore.loadAiPicks().map { pick ->
                    RecommendationPick(
                        market = pick.market,
                        ticker = pick.ticker,
                        name = pick.name,
                        basis = pick.basis,
                        confidence = pick.confidence,
                        note = pick.note,
                        expectedReturnRate = pick.expectedReturnRate,
                        source = "USER",
                        id = pick.id,
                    )
                },
                workspaceStore.loadAiTrackRecords().map { record ->
                    RecommendationTrackRecord(
                        recommendedDate = record.recommendedDate,
                        market = record.market,
                        ticker = record.ticker,
                        name = record.name,
                        entryPrice = record.entryPrice,
                        latestPrice = record.latestPrice,
                        realizedReturnRate = record.realizedReturnRate,
                        success = record.success,
                        source = "USER",
                        id = record.id,
                    )
                }
            ),
            quotes
        )
        return AiRecommendationsResponse(LocalDateTime.now().toString(), aiRecommendations)
    }

    fun getPaperTrading(): PaperTradingResponse {
        val quotes = loadKoreanQuotes()
        val paperTrading = refreshPaperTrading(
            mergePaperTrading(
                basePaperTrading(),
                workspaceStore.loadPaperPositions().map { position ->
                    PaperPosition(
                        market = position.market,
                        ticker = position.ticker,
                        name = position.name,
                        averagePrice = position.averagePrice,
                        currentPrice = position.currentPrice,
                        quantity = position.quantity,
                        returnRate = position.returnRate,
                        source = "USER",
                        id = position.id,
                    )
                },
                workspaceStore.loadPaperTrades().map { trade ->
                    PaperTrade(
                        tradeDate = trade.tradeDate,
                        side = trade.side,
                        market = trade.market,
                        ticker = trade.ticker,
                        name = trade.name,
                        price = trade.price,
                        quantity = trade.quantity,
                        source = "USER",
                        id = trade.id,
                    )
                }
            ),
            quotes
        )
        return PaperTradingResponse(LocalDateTime.now().toString(), paperTrading)
    }

    private fun buildUsMarket(vixSnapshot: VixSnapshot?, usIndicesSnapshot: UsIndicesSnapshot?): MarketSection {
        val nasdaq = usIndicesSnapshot?.nasdaq
        val sp500 = usIndicesSnapshot?.sp500

        return MarketSection(
                market = "US",
                title = "미국 시장",
                indices = listOf(
                    IndexMetric(
                        "NASDAQ",
                        nasdaq?.currentValue ?: 18342.40,
                        nasdaq?.changeRate ?: 1.12,
                        buildIndexChartPeriods(
                            latest = nasdaq?.currentValue ?: 18342.40,
                            changeRate = nasdaq?.changeRate ?: 1.12,
                            baseSeries = nasdaq?.chart ?: listOf(17840.0, 17910.0, 18040.0, 18160.0, 18250.0, 18342.0)
                        )
                    ),
                    IndexMetric(
                        "S&P 500",
                        sp500?.currentValue ?: 5224.60,
                        sp500?.changeRate ?: 0.74,
                        buildIndexChartPeriods(
                            latest = sp500?.currentValue ?: 5224.60,
                            changeRate = sp500?.changeRate ?: 0.74,
                            baseSeries = sp500?.chart ?: listOf(5110.0, 5140.0, 5168.0, 5181.0, 5202.0, 5224.0)
                        )
                    )
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

    private fun getCoreSnapshot(): CachedMarketCore {
        val cached = cachedCore
        if (cached != null && Duration.between(cached.createdAt, Instant.now()) < coreTtl) {
            return cached
        }

        return synchronized(this) {
            val rechecked = cachedCore
            if (rechecked != null && Duration.between(rechecked.createdAt, Instant.now()) < coreTtl) {
                return@synchronized rechecked
            }

            val generatedAt = LocalDateTime.now()
            val koreaMarketFuture = CompletableFuture.supplyAsync { krxOfficialClient.loadKoreaMarketSection() ?: defaultKoreaMarket() }
            val vixFuture = CompletableFuture.supplyAsync { cboeVixClient.fetchVix() }
            val koreanQuotesFuture = CompletableFuture.supplyAsync { loadKoreanQuotes() }
            val usIndicesFuture = CompletableFuture.supplyAsync { fredIndexClient.fetchUsIndices() }

            val koreaMarketBase = koreaMarketFuture.join()
            val vixSnapshot = vixFuture.join()
            val koreanQuotes = koreanQuotesFuture.join()
            val usIndicesSnapshot = usIndicesFuture.join()
            val pizzIntSnapshot = pizzIntClient.fetchSignals()
            val koreaMarket = koreaMarketBase.copy(
                leadingStocks = refreshKoreanLeadingStocks(koreaMarketBase.leadingStocks, koreanQuotes)
            )
            val marketSessions = buildMarketSessions()
            val snapshot = CachedMarketCore(
                createdAt = Instant.now(),
                generatedAt = generatedAt.toString(),
                marketStatus = buildMarketStatus(marketSessions),
                summary = "한국주식과 미국주식을 나눠 보고, 수급·공포지표·뉴스·포트폴리오·AI 추천을 분리해서 읽는 투자 대시보드야.",
                marketSummary = listOf(
                    SummaryMetric("Fear Meter", calculateFearMeter(vixSnapshot), buildFearMeterState(vixSnapshot), buildFearMeterNote(vixSnapshot)),
                    SummaryMetric("KR Heat", calculateKrHeat(koreaMarket), buildKrHeatState(koreaMarket), "코스피/코스닥 등락률과 거래 강도를 기준으로 계산"),
                    SummaryMetric("US Heat", 64.0, "AI/빅테크 중심", "미국 지수와 VIX를 기준으로 계산"),
                    SummaryMetric("Flow Bias", calculateFlowBias(koreaMarket), buildFlowBiasState(koreaMarket), "국내는 KRX 수급, 미국은 지수/변동성 기준")
                ),
                alternativeSignals = buildAlternativeSignals(pizzIntSnapshot),
                marketSessions = marketSessions,
                koreaMarket = koreaMarket,
                usMarket = buildUsMarket(vixSnapshot, usIndicesSnapshot),
                briefing = DailyBriefing(
                    headline = "오늘은 한국은 반도체, 미국은 빅테크가 중심이고, 과열 추격보다는 눌림 확인 후 진입이 맞아.",
                    preMarket = listOf(
                        "한국/미국 관심 종목 각각 3개만 우선순위 설정",
                        "KOSPI/KOSDAQ, NASDAQ/S&P 방향과 VIX 같이 확인",
                        "외국인/기관 수급이 붙는 종목만 먼저 본다"
                    ),
                    afterMarket = listOf(
                        "오늘 AI 추천 종목이 실제로 얼마나 움직였는지 복기",
                        "보유 종목 수익률과 수급 방향이 일치했는지 체크",
                        "내일은 한국/미국 각 2종목만 남기고 나머지는 관심 해제"
                    )
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

    private fun buildMarketStatus(sessions: List<MarketSessionStatus>): String {
        val kr = sessions.firstOrNull { it.market == "KR" }
        val us = sessions.firstOrNull { it.market == "US" }
        return when {
            kr?.phase == "REGULAR" -> "KR_REGULAR_OPEN"
            us?.phase == "REGULAR" -> "US_REGULAR_OPEN"
            kr?.phase == "PRE_MARKET" || us?.phase == "PRE_MARKET" -> "PRE_MARKET"
            kr?.phase == "AFTER_HOURS" || us?.phase == "AFTER_HOURS" -> "AFTER_HOURS"
            else -> "MARKET_CLOSED"
        }
    }

    private fun buildMarketSessions(nowUtc: ZonedDateTime = ZonedDateTime.now(ZoneId.of("UTC"))): List<MarketSessionStatus> {
        val koreaNow = nowUtc.withZoneSameInstant(ZoneId.of("Asia/Seoul"))
        val usNow = nowUtc.withZoneSameInstant(ZoneId.of("America/New_York"))
        return listOf(
            resolveSession(
                market = "KR",
                label = "한국",
                localNow = koreaNow,
                preStart = null,
                regularStart = LocalTime.of(9, 0),
                regularEnd = LocalTime.of(15, 30),
                afterEnd = null,
            ),
            resolveUsSession(usNow),
        )
    }

    private fun resolveUsSession(localNow: ZonedDateTime): MarketSessionStatus {
        val date = localNow.toLocalDate()
        val time = localNow.toLocalTime()
        val isWeekend = localNow.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val holiday = findUsHoliday(date)
        val earlyClose = findUsEarlyClose(date)

        if (isWeekend) {
            return closedSession("US", "미국", localNow, "주말 휴장")
        }
        if (holiday != null) {
            return closedSession("US", "미국", localNow, "${holiday.description} 휴장")
        }

        val regularStart = LocalTime.of(9, 30)
        val regularEnd = earlyClose?.closeTime ?: LocalTime.of(16, 0)
        val preStart = LocalTime.of(4, 0)
        val afterEnd = if (earlyClose != null) null else LocalTime.of(20, 0)

        return when {
            time >= preStart && time < regularStart -> MarketSessionStatus(
                market = "US",
                label = "미국",
                phase = "PRE_MARKET",
                status = "장전",
                isOpen = false,
                localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                note = if (earlyClose != null) "${earlyClose.description} (정규장 13:00 조기종료)" else "프리마켓 진행 중",
            )
            time >= regularStart && time < regularEnd -> MarketSessionStatus(
                market = "US",
                label = "미국",
                phase = "REGULAR",
                status = "정규장",
                isOpen = true,
                localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                note = if (earlyClose != null) "${earlyClose.description} 조기종료일 정규장 진행 중" else "정규장 진행 중",
            )
            afterEnd != null && time >= regularEnd && time < afterEnd -> MarketSessionStatus(
                market = "US",
                label = "미국",
                phase = "AFTER_HOURS",
                status = "장후",
                isOpen = false,
                localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                note = "애프터마켓 진행 중",
            )
            else -> closedSession(
                market = "US",
                label = "미국",
                localNow = localNow,
                reason = if (earlyClose != null) "${earlyClose.description} 조기종료(13:00) 후 마감" else "정규장 종료",
            )
        }
    }

    private fun resolveSession(
        market: String,
        label: String,
        localNow: ZonedDateTime,
        preStart: LocalTime?,
        regularStart: LocalTime,
        regularEnd: LocalTime,
        afterEnd: LocalTime?,
    ): MarketSessionStatus {
        val isWeekday = localNow.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val time = localNow.toLocalTime()
        val phase: String
        val status: String
        val isOpen: Boolean
        val note: String

        when {
            !isWeekday -> {
                phase = "CLOSED"
                status = "휴장"
                isOpen = false
                note = "주말 휴장"
            }
            preStart != null && time >= preStart && time < regularStart -> {
                phase = "PRE_MARKET"
                status = "장전"
                isOpen = false
                note = "프리마켓 진행 중"
            }
            time >= regularStart && time < regularEnd -> {
                phase = "REGULAR"
                status = "정규장"
                isOpen = true
                note = "정규장 진행 중"
            }
            afterEnd != null && time >= regularEnd && time < afterEnd -> {
                phase = "AFTER_HOURS"
                status = "장후"
                isOpen = false
                note = "애프터마켓 진행 중"
            }
            else -> {
                phase = "CLOSED"
                status = "마감"
                isOpen = false
                note = "정규장 종료"
            }
        }
        return MarketSessionStatus(
            market = market,
            label = label,
            phase = phase,
            status = status,
            isOpen = isOpen,
            localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
            note = note,
        )
    }

    private fun closedSession(
        market: String,
        label: String,
        localNow: ZonedDateTime,
        reason: String,
    ): MarketSessionStatus {
        return MarketSessionStatus(
            market = market,
            label = label,
            phase = "CLOSED",
            status = if (reason.contains("휴장")) "휴장" else "마감",
            isOpen = false,
            localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
            note = reason,
        )
    }

    private fun findUsHoliday(date: LocalDate): UsMarketSpecialDay? {
        val year = date.year
        val holidays = setOf(
            observedDate(LocalDate.of(year, Month.JANUARY, 1)),
            nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), // Martin Luther King Jr. Day
            nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), // Presidents' Day
            easterSunday(year).minusDays(2), // Good Friday
            lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY), // Memorial Day
            observedDate(LocalDate.of(year, Month.JUNE, 19)), // Juneteenth
            observedDate(LocalDate.of(year, Month.JULY, 4)), // Independence Day
            firstWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY), // Labor Day
            nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), // Thanksgiving
            observedDate(LocalDate.of(year, Month.DECEMBER, 25)), // Christmas
        )

        return if (date in holidays) {
            UsMarketSpecialDay(date, "미국 정규 휴장일")
        } else {
            null
        }
    }

    private fun findUsEarlyClose(date: LocalDate): UsMarketEarlyClose? {
        val year = date.year
        val thanksgiving = nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)
        val dayAfterThanksgiving = thanksgiving.plusDays(1)
        val christmasEve = LocalDate.of(year, Month.DECEMBER, 24)
        val independenceEve = LocalDate.of(year, Month.JULY, 3)

        val earlyCloseDates = buildList {
            if (dayAfterThanksgiving.dayOfWeek == DayOfWeek.FRIDAY) {
                add(UsMarketEarlyClose(dayAfterThanksgiving, "추수감사절 다음 날", LocalTime.of(13, 0)))
            }
            if (christmasEve.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
                add(UsMarketEarlyClose(christmasEve, "크리스마스 이브", LocalTime.of(13, 0)))
            }
            if (independenceEve.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && findUsHoliday(independenceEve) == null) {
                add(UsMarketEarlyClose(independenceEve, "독립기념일 전일", LocalTime.of(13, 0)))
            }
        }
        return earlyCloseDates.firstOrNull { it.date == date }
    }

    private fun observedDate(date: LocalDate): LocalDate {
        return when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> date.minusDays(1)
            DayOfWeek.SUNDAY -> date.plusDays(1)
            else -> date
        }
    }

    private fun firstWeekdayOfMonth(year: Int, month: Month, dayOfWeek: DayOfWeek): LocalDate {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.firstInMonth(dayOfWeek))
    }

    private fun nthWeekdayOfMonth(year: Int, month: Month, dayOfWeek: DayOfWeek, nth: Int): LocalDate {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(nth, dayOfWeek))
    }

    private fun lastWeekdayOfMonth(year: Int, month: Month, dayOfWeek: DayOfWeek): LocalDate {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dayOfWeek))
    }

    private fun easterSunday(year: Int): LocalDate {
        // Gregorian calendar (Meeus/Jones/Butcher algorithm)
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    data class UsMarketSpecialDay(
        val date: LocalDate,
        val description: String,
    )

    data class UsMarketEarlyClose(
        val date: LocalDate,
        val description: String,
        val closeTime: LocalTime,
    )

    private fun getCachedNews(): CachedNewsSection {
        val cached = cachedNews
        if (cached != null && Duration.between(cached.createdAt, Instant.now()) < newsTtl) {
            return cached
        }

        return synchronized(this) {
            val rechecked = cachedNews
            if (rechecked != null && Duration.between(rechecked.createdAt, Instant.now()) < newsTtl) {
                return@synchronized rechecked
            }

            val snapshot = CachedNewsSection(
                createdAt = Instant.now(),
                generatedAt = LocalDateTime.now().toString(),
                news = googleNewsRssClient.fetchMarketNews() ?: defaultNews(),
            )
            cachedNews = snapshot
            snapshot
        }
    }

    private fun buildWorkspaceCounts(): WorkspaceCounts {
        return WorkspaceCounts(
            watchlistCount = workspaceStore.loadWatchlist().size,
            portfolioCount = workspaceStore.loadPortfolioPositions().size,
            paperPositionCount = workspaceStore.loadPaperPositions().size,
            aiPickCount = workspaceStore.loadAiPicks().size,
        )
    }

    private fun loadKoreanQuotes(): Map<String, StockQuote> {
        return naverFinanceQuoteClient.fetchKoreanQuotes(buildQuoteUniverse())
    }

    private fun buildQuoteUniverse(): List<String> {
        val baseTickers = (baseWatchlist().asSequence().map { it.ticker } +
            basePortfolio().positions.asSequence().map { it.ticker } +
            baseAiRecommendations().trackRecords.asSequence().map { it.ticker } +
            basePaperTrading().openPositions.asSequence().map { it.ticker })
            .filter { it.all(Char::isDigit) }

        val userTickers = (workspaceStore.loadWatchlist().asSequence().map { it.ticker } +
            workspaceStore.loadPortfolioPositions().asSequence().map { it.ticker } +
            workspaceStore.loadAiTrackRecords().asSequence().map { it.ticker } +
            workspaceStore.loadPaperPositions().asSequence().map { it.ticker })
            .filter { it.all(Char::isDigit) }

        return (baseTickers + userTickers)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun defaultKoreaMarket(): MarketSection {
        return MarketSection(
            market = "KR",
                title = "한국 시장",
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
    }

    private fun defaultNews(): List<MarketNews> {
        return listOf(
            MarketNews("KR", "반도체 수출 기대감 유지, 외국인 자금 유입 확대", "매일경제", "https://www.mk.co.kr", "반도체 대형주 강세에 우호적"),
            MarketNews("KR", "코스닥 바이오 변동성 확대, 단기 수급 주의", "한국경제", "https://www.hankyung.com", "바이오 섹터는 눌림 확인 필요"),
            MarketNews("US", "미국 빅테크 실적 기대감에 나스닥 강세", "Reuters", "https://www.reuters.com", "AI 대형주 모멘텀 지속"),
            MarketNews("US", "연준 발언 앞두고 금리 민감주 혼조", "Bloomberg", "https://www.bloomberg.com", "금리 민감 업종은 변동성 대비 필요")
        )
    }

    private fun calculateKrHeat(koreaMarket: MarketSection): Double {
        val averageChange = koreaMarket.indices.map { it.changeRate }.average()
        return (50 + averageChange * 8).coerceIn(0.0, 100.0)
    }

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

    private fun buildAlternativeSignals(snapshot: PizzIntSnapshot?): List<AlternativeSignal> {
        if (snapshot == null) {
            return listOf(
                AlternativeSignal(
                    label = "Pentagon Pizza Index",
                    score = 58,
                    state = "관측 대기",
                    note = "PizzINT 공개 페이지 기반 실험 지표. 외부 응답 실패 시 기본값으로 표시",
                    highlights = listOf("DOUGHCON 대기", "외부 응답 fallback"),
                    source = "PizzINT",
                    url = "https://www.pizzint.watch/",
                    experimental = true,
                ),
                AlternativeSignal(
                    label = "Policy Buzz",
                    score = 52,
                    state = "보통",
                    note = "정책/안보 키워드 OSINT 강도와 뉴스 밀도를 함께 볼 예정",
                    highlights = listOf("OSINT feed 대기", "스파이크 평균 대기"),
                    source = "PizzINT + News",
                    url = "https://www.pizzint.watch/whitepaper",
                    experimental = true,
                ),
                AlternativeSignal(
                    label = "Bar Counter-Signal",
                    score = 44,
                    state = "관측 대기",
                    note = "Freddie's Beach Bar, The Little Gay Pub 같은 고정 venue 기반 bar proxy signal. direct venue source 연결 전 기본값으로 표시",
                    highlights = listOf("Freddie's 1.4mi", "Little Gay Pub 1.1mi", "proxy fallback"),
                    source = "Tracked Venue Proxy",
                    url = "https://www.pizzint.watch/whitepaper",
                    experimental = true,
                ),
            )
        }

        val spikingLocations = snapshot.locationSignals.count { extractSpikePercent(it.status) > 0 }
        val monitoredLocationCount = snapshot.monitoredLocationCount ?: snapshot.locationSignals.size
        val busiestLocation = snapshot.locationSignals.maxByOrNull { extractSpikePercent(it.status) }
        val maxSpike = busiestLocation?.let { extractSpikePercent(it.status) } ?: 0
        val averageSpike = snapshot.locationSignals
            .map { extractSpikePercent(it.status) }
            .filter { it > 0 }
            .average()
            .takeIf { !it.isNaN() }
            ?: 0.0

        val doughconScore = weightedScore(
            contributions = listOf(
                normalizedContribution(snapshot.doughconLevel?.toDouble() ?: 3.0, 5.0, 0.28),
                normalizedContribution(spikingLocations.toDouble(), monitoredLocationCount.coerceAtLeast(1).toDouble(), 0.34),
                normalizedContribution(maxSpike.toDouble(), 240.0, 0.38),
            ),
            floor = 14,
        )

        val alertScore = weightedScore(
            contributions = listOf(
                normalizedContribution((snapshot.reportCount ?: 0).toDouble(), 60.0, 0.4),
                normalizedContribution((snapshot.alertCount ?: 0).toDouble(), 20.0, 0.35),
                normalizedContribution(averageSpike, 220.0, 0.25),
            ),
            floor = 8,
        )

        val quietLocationCount = snapshot.locationSignals.count {
            val normalized = it.status.uppercase()
            normalized == "QUIET" || normalized == "NOMINAL" || normalized == "CLOSED"
        }
        val barCounterSignalScore = weightedScore(
            contributions = listOf(
                normalizedContribution(quietLocationCount.toDouble(), monitoredLocationCount.coerceAtLeast(1).toDouble(), 0.42),
                normalizedContribution((snapshot.alertCount ?: 0).toDouble(), 20.0, 0.33),
                normalizedContribution(maxSpike.toDouble(), 240.0, 0.25),
            ),
            floor = 12,
        )

        return listOf(
            AlternativeSignal(
                label = "Pentagon Pizza Index",
                score = doughconScore,
                state = buildPizzaState(doughconScore),
                note = buildPizzaNote(snapshot, busiestLocation, maxSpike, spikingLocations, monitoredLocationCount),
                highlights = listOf(
                    "DOUGHCON ${snapshot.doughconLevel ?: "-"}",
                    "급등 ${spikingLocations}/${monitoredLocationCount}",
                    "최고 ${maxSpike}%",
                ),
                source = "PizzINT",
                url = snapshot.sourceUrl,
                experimental = true,
            ),
            AlternativeSignal(
                label = "Policy Buzz",
                score = alertScore,
                state = buildPolicyBuzzState(alertScore),
                note = "OSINT 피드 ${snapshot.reportCount ?: 0}건, 알림 ${snapshot.alertCount ?: 0}건, 평균 스파이크 ${averageSpike.toInt()}%를 합산한 정책/안보 체감 지표",
                highlights = listOf(
                    "리포트 ${snapshot.reportCount ?: 0}",
                    "알림 ${snapshot.alertCount ?: 0}",
                    "평균 ${averageSpike.toInt()}%",
                ),
                source = "PizzINT OSINT Feed",
                url = snapshot.sourceUrl,
                experimental = true,
            ),
            AlternativeSignal(
                label = "Bar Counter-Signal",
                score = barCounterSignalScore,
                state = buildCounterSignalState(barCounterSignalScore),
                note = buildBarCounterSignalNote(
                    quietLocationCount = quietLocationCount,
                    monitoredLocationCount = monitoredLocationCount,
                    maxSpike = maxSpike,
                    trackedVenues = trackedBarVenues,
                ),
                highlights = listOf(
                    "조용함 ${quietLocationCount}/${monitoredLocationCount}",
                    "Freddie's 1.4mi",
                    "Little Gay Pub 1.1mi",
                ),
                source = "Tracked Venue Proxy",
                url = "https://www.pizzint.watch/whitepaper",
                experimental = true,
            ),
        )
    }

    private fun buildPizzaState(score: Int): String {
        return when {
            score >= 82 -> "고경계"
            score >= 64 -> "주의 강화"
            score >= 42 -> "보통"
            score >= 22 -> "낮음"
            else -> "평온"
        }
    }

    private fun buildPizzaNote(
        snapshot: PizzIntSnapshot,
        busiestLocation: PizzIntLocationSignal?,
        maxSpike: Int,
        spikingLocations: Int,
        monitoredLocationCount: Int,
    ): String {
        val locationPart = busiestLocation?.let { "${it.name} ${it.status}" } ?: "특정 급등 매장 없음"
        return "DOUGHCON ${snapshot.doughconLevel ?: "-"}, 급등 매장 ${spikingLocations}/${monitoredLocationCount}, 최고 스파이크 ${maxSpike}%, 대표 관측치: $locationPart"
    }

    private fun buildPolicyBuzzState(score: Int): String {
        return when {
            score >= 78 -> "고강도"
            score >= 58 -> "확대"
            score >= 34 -> "보통"
            else -> "잠잠"
        }
    }

    private fun buildCounterSignalState(score: Int): String {
        return when {
            score >= 74 -> "야간 비정상 징후"
            score >= 52 -> "내부 업무 모드 가능성"
            score >= 28 -> "혼재"
            else -> "특이 신호 약함"
        }
    }

    private fun buildBarCounterSignalNote(
        quietLocationCount: Int,
        monitoredLocationCount: Int,
        maxSpike: Int,
        trackedVenues: List<TrackedVenueProxy>,
    ): String {
        val venueSummary = trackedVenues.joinToString(", ") { "${it.name} ${it.distanceMiles}mi" }
        return "고정 venue(${venueSummary})를 기준으로 조용한 피자 매장 비율 ${quietLocationCount}/${monitoredLocationCount}, 최고 스파이크 ${maxSpike}%를 합산한 bar proxy signal. direct venue traffic source 확보 전까지 프록시로 운영"
    }

    private fun normalizedContribution(value: Double, scale: Double, weight: Double): Double {
        if (scale <= 0.0) return 0.0
        return (value / scale).coerceIn(0.0, 1.0) * weight
    }

    private fun weightedScore(contributions: List<Double>, floor: Int = 0): Int {
        val total = contributions.sum().coerceIn(0.0, 1.0)
        val curved = kotlin.math.sqrt(total)
        return (floor + curved * (100 - floor)).toInt().coerceIn(0, 100)
    }

    private fun extractSpikePercent(status: String): Int {
        return Regex("""(\d+)%""").find(status)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun refreshKoreanLeadingStocks(
        stocks: List<TickerSnapshot>,
        quotes: Map<String, StockQuote>,
    ): List<TickerSnapshot> {
        return stocks.map { stock ->
            quotes[stock.ticker]?.let { quote ->
                stock.copy(
                    price = quote.currentPrice,
                    changeRate = quote.changeRate,
                )
            } ?: stock
        }
    }

    private fun refreshWatchlist(
        items: List<WatchItem>,
        quotes: Map<String, StockQuote>,
    ): List<WatchItem> {
        return items.map { item ->
            if (item.market != "KR") return@map item
            quotes[item.ticker]?.let { quote ->
                item.copy(
                    price = quote.currentPrice,
                    changeRate = quote.changeRate,
                )
            } ?: item
        }.sortedWith(compareBy<WatchItem>({ it.market }, { it.name }))
    }

    private fun refreshPortfolio(
        portfolio: PortfolioSummary,
        quotes: Map<String, StockQuote>,
    ): PortfolioSummary {
        val previousEvaluation = portfolio.positions.associateBy({ it.ticker }) { it.evaluationAmount }
        val positions = portfolio.positions.map { position ->
            if (position.market != "KR") return@map position
            val quote = quotes[position.ticker] ?: return@map position
            val evaluationAmount = quote.currentPrice * position.quantity
            val costAmount = position.buyPrice * position.quantity
            val profitAmount = evaluationAmount - costAmount
            position.copy(
                currentPrice = quote.currentPrice,
                profitAmount = profitAmount,
                evaluationAmount = evaluationAmount,
                profitRate = if (costAmount == 0) 0.0 else (profitAmount.toDouble() / costAmount) * 100
            )
        }

        val delta = positions.sumOf { position ->
            position.evaluationAmount - (previousEvaluation[position.ticker] ?: position.evaluationAmount)
        }
        val totalValue = portfolio.totalValue + delta
        val totalProfit = totalValue - portfolio.totalCost

        return portfolio.copy(
            totalValue = totalValue,
            totalProfit = totalProfit,
            totalProfitRate = if (portfolio.totalCost == 0) 0.0 else (totalProfit.toDouble() / portfolio.totalCost) * 100,
            positions = positions.sortedWith(compareBy<HoldingPosition>({ it.market }, { it.name }))
        )
    }

    private fun mergePortfolio(
        basePortfolio: PortfolioSummary,
        workspacePositions: List<HoldingPosition>,
    ): PortfolioSummary {
        val positions = (basePortfolio.positions + workspacePositions)
            .sortedWith(compareBy<HoldingPosition>({ it.market }, { it.name }))
        val totalCost = positions.sumOf { it.buyPrice * it.quantity }
        val totalValue = positions.sumOf { it.currentPrice * it.quantity }
        val totalProfit = totalValue - totalCost

        return basePortfolio.copy(
            totalCost = totalCost,
            totalValue = totalValue,
            totalProfit = totalProfit,
            totalProfitRate = if (totalCost == 0) 0.0 else (totalProfit.toDouble() / totalCost) * 100,
            positions = positions
        )
    }

    private fun refreshAiRecommendations(
        section: AIRecommendationSection,
        quotes: Map<String, StockQuote>,
    ): AIRecommendationSection {
        val refreshedTrackRecords = section.trackRecords.map { record ->
            if (record.market != "KR") return@map record
            val quote = quotes[record.ticker] ?: return@map record
            val realizedReturnRate = if (record.entryPrice == 0) 0.0 else
                ((quote.currentPrice - record.entryPrice).toDouble() / record.entryPrice) * 100

            record.copy(
                latestPrice = quote.currentPrice,
                realizedReturnRate = realizedReturnRate,
                success = realizedReturnRate >= 0
            )
        }
        return section.copy(
            trackRecords = refreshedTrackRecords,
            executionLogs = buildRecommendationExecutionLogs(
                generatedDate = section.generatedDate,
                picks = section.picks,
                trackRecords = refreshedTrackRecords,
            ),
        )
    }

    private fun mergeAiRecommendations(
        baseSection: AIRecommendationSection,
        workspacePicks: List<RecommendationPick>,
        workspaceTrackRecords: List<RecommendationTrackRecord>,
    ): AIRecommendationSection {
        val picks = (baseSection.picks + workspacePicks)
            .sortedByDescending { it.confidence }
        val trackRecords = (baseSection.trackRecords + workspaceTrackRecords)
            .sortedByDescending { it.recommendedDate }
            .take(20)

        return baseSection.copy(
            picks = picks,
            trackRecords = trackRecords,
            executionLogs = buildRecommendationExecutionLogs(
                generatedDate = baseSection.generatedDate,
                picks = picks,
                trackRecords = trackRecords,
            ),
        )
    }

    private fun buildRecommendationExecutionLogs(
        generatedDate: String,
        picks: List<RecommendationPick>,
        trackRecords: List<RecommendationTrackRecord>,
    ): List<RecommendationExecutionLog> {
        val resultByTicker = trackRecords.associateBy { "${it.market}:${it.ticker}" }
        val pickLogs = picks.map { pick ->
            val match = resultByTicker["${pick.market}:${pick.ticker}"]
            RecommendationExecutionLog(
                date = generatedDate,
                market = pick.market,
                ticker = pick.ticker,
                name = pick.name,
                stage = "RECOMMEND",
                status = if (match != null) "검증완료" else "추적중",
                rationale = "${pick.basis} · ${pick.note}",
                confidence = pick.confidence,
                expectedReturnRate = pick.expectedReturnRate,
                realizedReturnRate = match?.realizedReturnRate,
                source = pick.source,
            )
        }

        val resultLogs = trackRecords
            .filter { record -> picks.none { it.market == record.market && it.ticker == record.ticker } }
            .map { record ->
                RecommendationExecutionLog(
                    date = record.recommendedDate,
                    market = record.market,
                    ticker = record.ticker,
                    name = record.name,
                    stage = "RESULT",
                    status = if (record.success) "검증완료" else "재평가필요",
                    rationale = "추천 성과 기록",
                    confidence = null,
                    expectedReturnRate = null,
                    realizedReturnRate = record.realizedReturnRate,
                    source = record.source,
                )
            }

        return (pickLogs + resultLogs)
            .sortedWith(compareByDescending<RecommendationExecutionLog> { it.date }.thenBy { it.market }.thenBy { it.ticker })
            .take(30)
    }

    private fun refreshPaperTrading(
        paperTrading: PaperTradingSummary,
        quotes: Map<String, StockQuote>,
    ): PaperTradingSummary {
        val previousValue = paperTrading.openPositions.associateBy({ it.ticker }) { it.currentPrice * it.quantity }
        val positions = paperTrading.openPositions.map { position ->
            if (position.market != "KR") return@map position
            val quote = quotes[position.ticker] ?: return@map position
            val returnRate = if (position.averagePrice == 0) 0.0 else
                ((quote.currentPrice - position.averagePrice).toDouble() / position.averagePrice) * 100

            position.copy(
                currentPrice = quote.currentPrice,
                returnRate = returnRate,
            )
        }

        val delta = positions.sumOf { position ->
            (position.currentPrice * position.quantity) - (previousValue[position.ticker] ?: position.currentPrice * position.quantity)
        }
        val evaluation = paperTrading.evaluation + delta

        return paperTrading.copy(
            evaluation = evaluation,
            totalReturnRate = if (paperTrading.evaluation == 0) 0.0 else ((evaluation - paperTrading.evaluation).toDouble() / paperTrading.evaluation) * 100,
            openPositions = positions.sortedWith(compareBy<PaperPosition>({ it.market }, { it.name }))
        )
    }

    private fun mergePaperTrading(
        basePaperTrading: PaperTradingSummary,
        workspacePositions: List<PaperPosition>,
        workspaceTrades: List<PaperTrade>,
    ): PaperTradingSummary {
        val openPositions = (basePaperTrading.openPositions + workspacePositions)
            .sortedWith(compareBy<PaperPosition>({ it.market }, { it.name }))
        val evaluation = openPositions.sumOf { it.currentPrice * it.quantity }
        val trades = (basePaperTrading.recentTrades + workspaceTrades)
            .sortedByDescending { it.tradeDate }
            .take(20)

        return basePaperTrading.copy(
            evaluation = evaluation,
            openPositions = openPositions,
            recentTrades = trades
        )
    }

    private fun calculateFearMeter(vixSnapshot: VixSnapshot?): Double {
        val vix = vixSnapshot?.currentPrice ?: 17.0
        return (100 - ((vix - 12) * 4)).coerceIn(0.0, 100.0)
    }

    private fun buildFearMeterState(vixSnapshot: VixSnapshot?): String {
        val vix = vixSnapshot?.currentPrice ?: return "중립"
        return when {
            vix < 15 -> "낙관 우위"
            vix < 20 -> "위험선호 우위"
            vix < 26 -> "경계 구간"
            else -> "공포 확대"
        }
    }

    private fun buildFearMeterNote(vixSnapshot: VixSnapshot?): String {
        val vix = vixSnapshot?.currentPrice ?: return "VIX 실데이터 미연결 상태라 기본 공포 점수를 사용 중"
        return "공식 CBOE VIX ${"%.2f".format(vix)} 기준으로 계산한 미국 시장 위험심리"
    }

    private fun calculateVixScore(vixSnapshot: VixSnapshot?): Int {
        return calculateFearMeter(vixSnapshot).toInt()
    }

    private fun buildVixState(vixSnapshot: VixSnapshot?): String {
        return when (buildFearMeterState(vixSnapshot)) {
            "낙관 우위", "위험선호 우위" -> "낮음"
            "경계 구간" -> "중간"
            else -> "높음"
        }
    }

    private fun buildVixNote(vixSnapshot: VixSnapshot?): String {
        val vix = vixSnapshot?.currentPrice ?: return "VIX 기본값 기반"
        val change = vixSnapshot.priceChange
        val signedChange = if (change > 0) "+${"%.2f".format(change)}" else "%.2f".format(change)
        return "CBOE VIX ${"%.2f".format(vix)} (${signedChange}) 기준 위험심리"
    }

    private fun baseWatchlist(): List<WatchItem> {
        return listOf(
            WatchItem("KR", "005930", "삼성전자", 84200, 1.44, "반도체", "관심 유지", "실적 기대감이 가격 방어 역할"),
            WatchItem("KR", "000660", "SK하이닉스", 201500, 2.11, "반도체", "강한 흐름", "AI 메모리 기대감 유지"),
            WatchItem("US", "NVDA", "NVIDIA", 945, 2.84, "AI 반도체", "모멘텀 관찰", "신고가 부근이라 추격보다 눌림 체크"),
            WatchItem("US", "MSFT", "Microsoft", 428, 0.91, "플랫폼", "안정 관심", "나스닥 강세 구간에서 방어적")
        )
    }

    private fun basePortfolio(): PortfolioSummary {
        return PortfolioSummary(
            totalCost = 12840000,
            totalValue = 13765000,
            totalProfit = 925000,
            totalProfitRate = 7.2,
            positions = listOf(
                HoldingPosition("KR", "005930", "삼성전자", 78000, 84200, 12, 74400, 772800, 8.53),
                HoldingPosition("KR", "000660", "SK하이닉스", 188000, 201500, 4, 54000, 806000, 7.18),
                HoldingPosition("US", "MSFT", "Microsoft", 401, 428, 5, 135, 2140, 6.73)
            )
        )
    }

    private fun baseAiRecommendations(): AIRecommendationSection {
        val picks = listOf(
            RecommendationPick("KR", "000660", "SK하이닉스", "외국인 수급 + 섹터 강도 + 뉴스 일치", 78, "반도체 대형주 주도 구간에서 가장 강도가 좋음", 4.6),
            RecommendationPick("US", "NVDA", "NVIDIA", "AI 대장주 모멘텀 유지", 74, "나스닥 강세와 섹터 뉴스가 동시에 받쳐줌", 6.8),
            RecommendationPick("KR", "105560", "KB금융", "금융 저평가 + 수급 안정", 61, "변동성 방어 대안", 2.1)
        )
        val trackRecords = listOf(
            RecommendationTrackRecord("2026-04-02", "KR", "005930", "삼성전자", 80100, 84200, 5.11, true),
            RecommendationTrackRecord("2026-04-03", "US", "MSFT", "Microsoft", 412, 428, 3.88, true),
            RecommendationTrackRecord("2026-04-04", "KR", "068270", "셀트리온", 181000, 176200, -2.65, false)
        )

        return AIRecommendationSection(
            generatedDate = LocalDate.now().toString(),
            summary = "수급과 뉴스, 섹터 강도 기준으로 오늘은 반도체와 빅테크 중심 추세 추종이 유리한 날로 본다.",
            picks = picks,
            trackRecords = trackRecords,
            executionLogs = buildRecommendationExecutionLogs(
                generatedDate = LocalDate.now().toString(),
                picks = picks,
                trackRecords = trackRecords,
            ),
        )
    }

    private fun basePaperTrading(): PaperTradingSummary {
        return PaperTradingSummary(
            cash = 5000000,
            evaluation = 5348000,
            totalReturnRate = 6.96,
            openPositions = listOf(
                PaperPosition("KR", "005930", "삼성전자", 79000, 84200, 3, 5.06),
                PaperPosition("US", "AMZN", "Amazon", 176, 184, 2, 4.54)
            ),
            recentTrades = listOf(
                PaperTrade("2026-04-07", "BUY", "KR", "005930", "삼성전자", 79000, 3),
                PaperTrade("2026-04-08", "BUY", "US", "AMZN", "Amazon", 176, 2),
                PaperTrade("2026-04-08", "SELL", "KR", "NAVER", "NAVER", 186000, 1)
            )
        )
    }
}

data class MarketOverviewResponse(
    val generatedAt: String,
    val marketStatus: String,
    val summary: String,
    val marketSummary: List<SummaryMetric>,
    val alternativeSignals: List<AlternativeSignal>,
    val marketSessions: List<MarketSessionStatus>,
    val koreaMarket: MarketSection,
    val usMarket: MarketSection,
    val news: List<MarketNews>,
    val watchlist: List<WatchItem>,
    val portfolio: PortfolioSummary,
    val aiRecommendations: AIRecommendationSection,
    val paperTrading: PaperTradingSummary,
    val briefing: DailyBriefing,
    val sourceNotes: List<SourceNote>
)

data class MarketSummaryResponse(
    val generatedAt: String,
    val marketStatus: String,
    val summary: String,
    val marketSummary: List<SummaryMetric>,
    val alternativeSignals: List<AlternativeSignal>,
    val marketSessions: List<MarketSessionStatus>,
    val briefing: DailyBriefing,
    val sourceNotes: List<SourceNote>,
    val workspaceCounts: WorkspaceCounts,
)

data class MarketSectionsResponse(
    val generatedAt: String,
    val koreaMarket: MarketSection,
    val usMarket: MarketSection,
)

data class NewsFeedResponse(
    val generatedAt: String,
    val news: List<MarketNews>,
)

data class WatchlistResponse(
    val generatedAt: String,
    val watchlist: List<WatchItem>,
)

data class PortfolioResponse(
    val generatedAt: String,
    val portfolio: PortfolioSummary,
)

data class AiRecommendationsResponse(
    val generatedAt: String,
    val aiRecommendations: AIRecommendationSection,
)

data class PaperTradingResponse(
    val generatedAt: String,
    val paperTrading: PaperTradingSummary,
)

data class WorkspaceCounts(
    val watchlistCount: Int,
    val portfolioCount: Int,
    val paperPositionCount: Int,
    val aiPickCount: Int,
)

data class SummaryMetric(
    val label: String,
    val score: Double,
    val state: String,
    val note: String
)

data class TrackedVenueProxy(
    val name: String,
    val anchor: String,
    val distanceMiles: Double,
)

data class AlternativeSignal(
    val label: String,
    val score: Int,
    val state: String,
    val note: String,
    val highlights: List<String>,
    val source: String,
    val url: String,
    val experimental: Boolean,
)

data class MarketSessionStatus(
    val market: String,
    val label: String,
    val phase: String,
    val status: String,
    val isOpen: Boolean,
    val localTime: String,
    val note: String,
)

data class MarketSection(
    val market: String,
    val title: String,
    val indices: List<IndexMetric>,
    val sentiment: List<SentimentMetric>,
    val investorFlows: List<InvestorFlow>,
    val leadingStocks: List<TickerSnapshot>
)

data class IndexMetric(
    val label: String,
    val value: Double,
    val changeRate: Double,
    val periods: List<ChartPeriodSnapshot>
)

data class ChartPeriodSnapshot(
    val key: String,
    val label: String,
    val points: List<ChartPoint>,
    val stats: ChartStats,
)

data class ChartPoint(
    val label: String,
    val value: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

data class ChartStats(
    val latest: Double,
    val high: Double,
    val low: Double,
    val changeRate: Double,
    val range: Double,
    val averageVolume: Long,
)

data class SentimentMetric(
    val label: String,
    val state: String,
    val score: Int,
    val note: String
)

data class InvestorFlow(
    val investor: String,
    val amountBillionWon: Double,
    val note: String,
    val positive: Boolean
)

data class TickerSnapshot(
    val ticker: String,
    val name: String,
    val sector: String,
    val price: Int,
    val changeRate: Double,
    val stance: String
)

data class MarketNews(
    val market: String,
    val title: String,
    val source: String,
    val url: String,
    val impact: String
)

data class WatchItem(
    val market: String,
    val ticker: String,
    val name: String,
    val price: Int,
    val changeRate: Double,
    val sector: String,
    val stance: String,
    val note: String,
    val source: String = "BASE",
    val id: String = "",
)

data class PortfolioSummary(
    val totalCost: Int,
    val totalValue: Int,
    val totalProfit: Int,
    val totalProfitRate: Double,
    val positions: List<HoldingPosition>
)

data class HoldingPosition(
    val market: String,
    val ticker: String,
    val name: String,
    val buyPrice: Int,
    val currentPrice: Int,
    val quantity: Int,
    val profitAmount: Int,
    val evaluationAmount: Int,
    val profitRate: Double,
    val source: String = "BASE",
    val id: String = "",
)

data class AIRecommendationSection(
    val generatedDate: String,
    val summary: String,
    val picks: List<RecommendationPick>,
    val trackRecords: List<RecommendationTrackRecord>,
    val executionLogs: List<RecommendationExecutionLog>,
)

data class RecommendationPick(
    val market: String,
    val ticker: String,
    val name: String,
    val basis: String,
    val confidence: Int,
    val note: String,
    val expectedReturnRate: Double,
    val source: String = "BASE",
    val id: String = "",
)

data class RecommendationTrackRecord(
    val recommendedDate: String,
    val market: String,
    val ticker: String,
    val name: String,
    val entryPrice: Int,
    val latestPrice: Int,
    val realizedReturnRate: Double,
    val success: Boolean,
    val source: String = "BASE",
    val id: String = "",
)

data class RecommendationExecutionLog(
    val date: String,
    val market: String,
    val ticker: String,
    val name: String,
    val stage: String,
    val status: String,
    val rationale: String,
    val confidence: Int?,
    val expectedReturnRate: Double?,
    val realizedReturnRate: Double?,
    val source: String = "BASE",
)

data class PaperTradingSummary(
    val cash: Int,
    val evaluation: Int,
    val totalReturnRate: Double,
    val openPositions: List<PaperPosition>,
    val recentTrades: List<PaperTrade>
)

data class PaperPosition(
    val market: String,
    val ticker: String,
    val name: String,
    val averagePrice: Int,
    val currentPrice: Int,
    val quantity: Int,
    val returnRate: Double,
    val source: String = "BASE",
    val id: String = "",
)

data class PaperTrade(
    val tradeDate: String,
    val side: String,
    val market: String,
    val ticker: String,
    val name: String,
    val price: Int,
    val quantity: Int,
    val source: String = "BASE",
    val id: String = "",
)

data class DailyBriefing(
    val headline: String,
    val preMarket: List<String>,
    val afterMarket: List<String>
)

data class SourceNote(
    val label: String,
    val source: String,
    val url: String
)

private data class CachedMarketCore(
    val createdAt: Instant,
    val generatedAt: String,
    val marketStatus: String,
    val summary: String,
    val marketSummary: List<SummaryMetric>,
    val alternativeSignals: List<AlternativeSignal>,
    val marketSessions: List<MarketSessionStatus>,
    val koreaMarket: MarketSection,
    val usMarket: MarketSection,
    val briefing: DailyBriefing,
    val sourceNotes: List<SourceNote>,
)

private data class CachedNewsSection(
    val createdAt: Instant,
    val generatedAt: String,
    val news: List<MarketNews>,
)
