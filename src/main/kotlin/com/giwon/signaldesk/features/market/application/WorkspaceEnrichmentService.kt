package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class WorkspaceEnrichmentService(
    private val naverFinanceQuoteClient: NaverFinanceQuoteClient,
    private val workspaceStore: SignalDeskWorkspaceRepository,
) {

    fun loadKoreanQuotes(): Map<String, StockQuote> =
        naverFinanceQuoteClient.fetchKoreanQuotes(buildQuoteUniverse())

    fun buildWorkspaceCounts() = WorkspaceCounts(
        watchlistCount = workspaceStore.loadWatchlist().size,
        portfolioCount = workspaceStore.loadPortfolioPositions().size,
        paperPositionCount = workspaceStore.loadPaperPositions().size,
        aiPickCount = workspaceStore.loadAiPicks().size,
    )

    fun getWatchlist(quotes: Map<String, StockQuote> = loadKoreanQuotes()): WatchlistResponse {
        val merged = baseWatchlist() + workspaceStore.loadWatchlist().map {
            WatchItem(id = it.id, market = it.market, ticker = it.ticker, name = it.name,
                price = it.price, changeRate = it.changeRate, sector = it.sector,
                stance = it.stance, note = it.note, source = "USER")
        }
        return WatchlistResponse(LocalDateTime.now().toString(), refreshWatchlist(merged, quotes))
    }

    fun getPortfolio(quotes: Map<String, StockQuote> = loadKoreanQuotes()): PortfolioResponse {
        val merged = mergePortfolio(basePortfolio(), workspaceStore.loadPortfolioPositions().map {
            HoldingPosition(id = it.id, market = it.market, ticker = it.ticker, name = it.name,
                buyPrice = it.buyPrice, currentPrice = it.currentPrice, quantity = it.quantity,
                profitAmount = it.profitAmount, evaluationAmount = it.evaluationAmount,
                profitRate = it.profitRate, source = "USER")
        })
        return PortfolioResponse(LocalDateTime.now().toString(), refreshPortfolio(merged, quotes))
    }

    fun getAiRecommendations(quotes: Map<String, StockQuote> = loadKoreanQuotes()): AiRecommendationsResponse {
        val merged = mergeAiRecommendations(
            baseAiRecommendations(),
            workspaceStore.loadAiPicks().map {
                RecommendationPick(market = it.market, ticker = it.ticker, name = it.name,
                    basis = it.basis, confidence = it.confidence, note = it.note,
                    expectedReturnRate = it.expectedReturnRate, source = "USER", id = it.id)
            },
            workspaceStore.loadAiTrackRecords().map {
                RecommendationTrackRecord(recommendedDate = it.recommendedDate, market = it.market,
                    ticker = it.ticker, name = it.name, entryPrice = it.entryPrice,
                    latestPrice = it.latestPrice, realizedReturnRate = it.realizedReturnRate,
                    success = it.success, source = "USER", id = it.id)
            }
        )
        return AiRecommendationsResponse(LocalDateTime.now().toString(), refreshAiRecommendations(merged, quotes))
    }

    fun getPaperTrading(quotes: Map<String, StockQuote> = loadKoreanQuotes()): PaperTradingResponse {
        val merged = mergePaperTrading(
            basePaperTrading(),
            workspaceStore.loadPaperPositions().map {
                PaperPosition(market = it.market, ticker = it.ticker, name = it.name,
                    averagePrice = it.averagePrice, currentPrice = it.currentPrice,
                    quantity = it.quantity, returnRate = it.returnRate, source = "USER", id = it.id)
            },
            workspaceStore.loadPaperTrades().map {
                PaperTrade(tradeDate = it.tradeDate, side = it.side, market = it.market,
                    ticker = it.ticker, name = it.name, price = it.price,
                    quantity = it.quantity, source = "USER", id = it.id)
            }
        )
        return PaperTradingResponse(LocalDateTime.now().toString(), refreshPaperTrading(merged, quotes))
    }

    fun buildWorkspaceSnapshot(quotes: Map<String, StockQuote>): WorkspaceSnapshot {
        return WorkspaceSnapshot(
            watchlist = getWatchlist(quotes).watchlist,
            portfolio = getPortfolio(quotes).portfolio,
            aiRecommendations = getAiRecommendations(quotes).aiRecommendations,
        )
    }

    fun refreshKoreanLeadingStocks(stocks: List<TickerSnapshot>, quotes: Map<String, StockQuote>): List<TickerSnapshot> =
        stocks.map { stock ->
            quotes[stock.ticker]?.let { quote ->
                stock.copy(price = quote.currentPrice, changeRate = quote.changeRate)
            } ?: stock
        }

    private fun refreshWatchlist(items: List<WatchItem>, quotes: Map<String, StockQuote>): List<WatchItem> =
        items.map { item ->
            if (item.market != "KR") return@map item
            quotes[item.ticker]?.let { quote ->
                item.copy(price = quote.currentPrice, changeRate = quote.changeRate)
            } ?: item
        }.sortedWith(compareBy({ it.market }, { it.name }))

    private fun refreshPortfolio(portfolio: PortfolioSummary, quotes: Map<String, StockQuote>): PortfolioSummary {
        val previousEvaluation = portfolio.positions.associateBy({ it.ticker }) { it.evaluationAmount }
        val positions = portfolio.positions.map { position ->
            if (position.market != "KR") return@map position
            val quote = quotes[position.ticker] ?: return@map position
            val evaluationAmount = quote.currentPrice.toLong() * position.quantity
            val costAmount = position.buyPrice.toLong() * position.quantity
            val profitAmount = evaluationAmount - costAmount
            position.copy(
                currentPrice = quote.currentPrice, profitAmount = profitAmount,
                evaluationAmount = evaluationAmount,
                profitRate = if (costAmount == 0L) 0.0 else (profitAmount.toDouble() / costAmount) * 100
            )
        }
        val delta = positions.sumOf { position ->
            position.evaluationAmount - (previousEvaluation[position.ticker] ?: position.evaluationAmount)
        }
        val totalValue = portfolio.totalValue + delta
        val totalProfit = totalValue - portfolio.totalCost
        return portfolio.copy(
            totalValue = totalValue, totalProfit = totalProfit,
            totalProfitRate = if (portfolio.totalCost == 0L) 0.0 else (totalProfit.toDouble() / portfolio.totalCost) * 100,
            positions = positions.sortedWith(compareBy({ it.market }, { it.name }))
        )
    }

    private fun mergePortfolio(base: PortfolioSummary, workspace: List<HoldingPosition>): PortfolioSummary {
        val positions = (base.positions + workspace).sortedWith(compareBy({ it.market }, { it.name }))
        val totalCost = positions.sumOf { it.buyPrice.toLong() * it.quantity }
        val totalValue = positions.sumOf { it.currentPrice.toLong() * it.quantity }
        val totalProfit = totalValue - totalCost
        return base.copy(
            totalCost = totalCost, totalValue = totalValue, totalProfit = totalProfit,
            totalProfitRate = if (totalCost == 0L) 0.0 else (totalProfit.toDouble() / totalCost) * 100,
            positions = positions
        )
    }

    private fun refreshAiRecommendations(section: AIRecommendationSection, quotes: Map<String, StockQuote>): AIRecommendationSection {
        val refreshedTrackRecords = section.trackRecords.map { record ->
            if (record.market != "KR") return@map record
            val quote = quotes[record.ticker] ?: return@map record
            val realizedReturnRate = if (record.entryPrice == 0) 0.0
            else ((quote.currentPrice - record.entryPrice).toDouble() / record.entryPrice) * 100
            record.copy(latestPrice = quote.currentPrice, realizedReturnRate = realizedReturnRate, success = realizedReturnRate >= 0)
        }
        return section.copy(
            trackRecords = refreshedTrackRecords,
            executionLogs = buildRecommendationExecutionLogs(section.generatedDate, section.picks, refreshedTrackRecords),
        )
    }

    private fun mergeAiRecommendations(
        base: AIRecommendationSection,
        workspacePicks: List<RecommendationPick>,
        workspaceTrackRecords: List<RecommendationTrackRecord>,
    ): AIRecommendationSection {
        val picks = (base.picks + workspacePicks).sortedByDescending { it.confidence }
        val trackRecords = (base.trackRecords + workspaceTrackRecords).sortedByDescending { it.recommendedDate }.take(20)
        return base.copy(
            picks = picks, trackRecords = trackRecords,
            executionLogs = buildRecommendationExecutionLogs(base.generatedDate, picks, trackRecords),
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
                date = generatedDate, market = pick.market, ticker = pick.ticker, name = pick.name,
                stage = "RECOMMEND", status = if (match != null) "검증완료" else "추적중",
                rationale = "${pick.basis} · ${pick.note}", confidence = pick.confidence,
                expectedReturnRate = pick.expectedReturnRate, realizedReturnRate = match?.realizedReturnRate,
                source = pick.source,
            )
        }
        val resultLogs = trackRecords
            .filter { record -> picks.none { it.market == record.market && it.ticker == record.ticker } }
            .map { record ->
                RecommendationExecutionLog(
                    date = record.recommendedDate, market = record.market, ticker = record.ticker, name = record.name,
                    stage = "RESULT", status = if (record.success) "검증완료" else "재평가필요",
                    rationale = "추천 성과 기록", confidence = null, expectedReturnRate = null,
                    realizedReturnRate = record.realizedReturnRate, source = record.source,
                )
            }
        return (pickLogs + resultLogs)
            .sortedWith(compareByDescending<RecommendationExecutionLog> { it.date }.thenBy { it.market }.thenBy { it.ticker })
            .take(30)
    }

    private fun refreshPaperTrading(paperTrading: PaperTradingSummary, quotes: Map<String, StockQuote>): PaperTradingSummary {
        val previousValue = paperTrading.openPositions.associateBy({ it.ticker }) { it.currentPrice.toLong() * it.quantity }
        val positions = paperTrading.openPositions.map { position ->
            if (position.market != "KR") return@map position
            val quote = quotes[position.ticker] ?: return@map position
            val returnRate = if (position.averagePrice == 0) 0.0
            else ((quote.currentPrice - position.averagePrice).toDouble() / position.averagePrice) * 100
            position.copy(currentPrice = quote.currentPrice, returnRate = returnRate)
        }
        val delta = positions.sumOf { position ->
            (position.currentPrice.toLong() * position.quantity) - (previousValue[position.ticker] ?: position.currentPrice.toLong() * position.quantity)
        }
        val evaluation = paperTrading.evaluation + delta
        // totalReturnRate = 현재 평가액 대비 취득 원가 기준 수익률
        val costBasis = positions.sumOf { it.averagePrice.toLong() * it.quantity }
        val totalReturnRate = if (costBasis == 0L) 0.0 else ((evaluation - costBasis).toDouble() / costBasis) * 100
        return paperTrading.copy(
            evaluation = evaluation,
            totalReturnRate = totalReturnRate,
            openPositions = positions.sortedWith(compareBy({ it.market }, { it.name }))
        )
    }

    private fun mergePaperTrading(
        base: PaperTradingSummary,
        workspacePositions: List<PaperPosition>,
        workspaceTrades: List<PaperTrade>,
    ): PaperTradingSummary {
        val openPositions = (base.openPositions + workspacePositions).sortedWith(compareBy({ it.market }, { it.name }))
        val trades = (base.recentTrades + workspaceTrades).sortedByDescending { it.tradeDate }.take(20)
        return base.copy(
            evaluation = openPositions.sumOf { it.currentPrice.toLong() * it.quantity },
            openPositions = openPositions, recentTrades = trades
        )
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
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()
    }

    // ─── Base / Seed Data ────────────────────────────────────────────────────

    fun baseWatchlist(): List<WatchItem> = listOf(
        WatchItem("KR", "005930", "삼성전자", 84200, 1.44, "반도체", "관심 유지", "실적 기대감이 가격 방어 역할"),
        WatchItem("KR", "000660", "SK하이닉스", 201500, 2.11, "반도체", "강한 흐름", "AI 메모리 기대감 유지"),
        WatchItem("US", "NVDA", "NVIDIA", 945, 2.84, "AI 반도체", "모멘텀 관찰", "신고가 부근이라 추격보다 눌림 체크"),
        WatchItem("US", "MSFT", "Microsoft", 428, 0.91, "플랫폼", "안정 관심", "나스닥 강세 구간에서 방어적")
    )

    fun basePortfolio(): PortfolioSummary = PortfolioSummary(
        totalCost = 12840000L, totalValue = 13765000L, totalProfit = 925000L, totalProfitRate = 7.2,
        positions = listOf(
            HoldingPosition("KR", "005930", "삼성전자", 78000, 84200, 12, 74400L, 1010400L, 8.53),
            HoldingPosition("KR", "000660", "SK하이닉스", 188000, 201500, 4, 54000L, 806000L, 7.18),
            HoldingPosition("US", "MSFT", "Microsoft", 401, 428, 5, 135L, 2140L, 6.73)
        )
    )

    fun baseAiRecommendations(): AIRecommendationSection {
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
            picks = picks, trackRecords = trackRecords,
            executionLogs = buildRecommendationExecutionLogs(LocalDate.now().toString(), picks, trackRecords),
        )
    }

    fun basePaperTrading(): PaperTradingSummary = PaperTradingSummary(
        cash = 5000000, evaluation = 5348000L, totalReturnRate = 6.96,
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
