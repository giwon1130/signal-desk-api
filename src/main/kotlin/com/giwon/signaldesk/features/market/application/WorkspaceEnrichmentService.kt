package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Service
class WorkspaceEnrichmentService(
    private val naverFinanceQuoteClient: NaverFinanceQuoteClient,
    private val refresher: WorkspaceQuoteRefresher,
    private val workspaceStore: SignalDeskWorkspaceRepository,
) {

    fun loadKoreanQuotes(userId: UUID? = null): Map<String, StockQuote> =
        naverFinanceQuoteClient.fetchKoreanQuotes(buildQuoteUniverse(userId))

    fun buildWorkspaceCounts(userId: UUID? = null) = WorkspaceCounts(
        watchlistCount = workspaceStore.loadWatchlist(userId).size,
        portfolioCount = workspaceStore.loadPortfolioPositions(userId).size,
        paperPositionCount = workspaceStore.loadPaperPositions(userId).size,
        aiPickCount = workspaceStore.loadAiPicks(userId).size,
    )

    fun getWatchlist(userId: UUID? = null, quotes: Map<String, StockQuote> = loadKoreanQuotes(userId)): WatchlistResponse {
        val items = workspaceStore.loadWatchlist(userId).map {
            WatchItem(id = it.id, market = it.market, ticker = it.ticker, name = it.name,
                price = it.price, changeRate = it.changeRate, sector = it.sector,
                stance = it.stance, note = it.note, source = "USER")
        }
        return WatchlistResponse(LocalDateTime.now().toString(), refresher.refreshWatchlist(items, quotes))
    }

    fun getPortfolio(userId: UUID? = null, quotes: Map<String, StockQuote> = loadKoreanQuotes(userId)): PortfolioResponse {
        val userPositions = workspaceStore.loadPortfolioPositions(userId).map {
            HoldingPosition(id = it.id, market = it.market, ticker = it.ticker, name = it.name,
                buyPrice = it.buyPrice, currentPrice = it.currentPrice, quantity = it.quantity,
                profitAmount = it.profitAmount, evaluationAmount = it.evaluationAmount,
                profitRate = it.profitRate, source = "USER",
                targetPrice = it.targetPrice, stopLossPrice = it.stopLossPrice)
        }
        val merged = mergePortfolio(emptyPortfolio(), userPositions)
        return PortfolioResponse(LocalDateTime.now().toString(), refresher.refreshPortfolio(merged, quotes))
    }

    fun getAiRecommendations(userId: UUID? = null, quotes: Map<String, StockQuote> = loadKoreanQuotes(userId)): AiRecommendationsResponse {
        val userPicks = workspaceStore.loadAiPicks(userId).map {
            RecommendationPick(market = it.market, ticker = it.ticker, name = it.name,
                basis = it.basis, confidence = it.confidence, note = it.note,
                expectedReturnRate = it.expectedReturnRate, source = "USER", id = it.id)
        }
        val userTrack = workspaceStore.loadAiTrackRecords(userId).map {
            RecommendationTrackRecord(recommendedDate = it.recommendedDate, market = it.market,
                ticker = it.ticker, name = it.name, entryPrice = it.entryPrice,
                latestPrice = it.latestPrice, realizedReturnRate = it.realizedReturnRate,
                success = it.success, source = "USER", id = it.id)
        }
        val merged = mergeAiRecommendations(emptyAiRecommendations(), userPicks, userTrack)
        return AiRecommendationsResponse(LocalDateTime.now().toString(), refresher.refreshAiRecommendations(merged, quotes))
    }

    fun getPaperTrading(userId: UUID? = null, quotes: Map<String, StockQuote> = loadKoreanQuotes(userId)): PaperTradingResponse {
        val userPositions = workspaceStore.loadPaperPositions(userId).map {
            PaperPosition(market = it.market, ticker = it.ticker, name = it.name,
                averagePrice = it.averagePrice, currentPrice = it.currentPrice,
                quantity = it.quantity, returnRate = it.returnRate, source = "USER", id = it.id)
        }
        val userTrades = workspaceStore.loadPaperTrades(userId).map {
            PaperTrade(tradeDate = it.tradeDate, side = it.side, market = it.market,
                ticker = it.ticker, name = it.name, price = it.price,
                quantity = it.quantity, source = "USER", id = it.id)
        }
        val merged = mergePaperTrading(emptyPaperTrading(), userPositions, userTrades)
        return PaperTradingResponse(LocalDateTime.now().toString(), refresher.refreshPaperTrading(merged, quotes))
    }

    fun buildWorkspaceSnapshot(quotes: Map<String, StockQuote>, userId: UUID? = null): WorkspaceSnapshot {
        return WorkspaceSnapshot(
            watchlist = getWatchlist(userId, quotes).watchlist,
            portfolio = getPortfolio(userId, quotes).portfolio,
            aiRecommendations = getAiRecommendations(userId, quotes).aiRecommendations,
        )
    }

    fun refreshKoreanLeadingStocks(stocks: List<TickerSnapshot>, quotes: Map<String, StockQuote>): List<TickerSnapshot> =
        refresher.refreshKoreanLeadingStocks(stocks, quotes)

    // ─── Merge (base + workspace) ────────────────────────────────────────────

    private fun mergePortfolio(base: PortfolioSummary, workspace: List<HoldingPosition>): PortfolioSummary {
        val positions = (base.positions + workspace).sortedWith(compareBy({ it.market }, { it.name }))
        val totalCost = positions.sumOf { it.buyPrice.toLong() * it.quantity }
        val totalValue = positions.sumOf { it.currentPrice.toLong() * it.quantity }
        val totalProfit = totalValue - totalCost
        return base.copy(
            totalCost = totalCost, totalValue = totalValue, totalProfit = totalProfit,
            totalProfitRate = if (totalCost == 0L) 0.0 else (totalProfit.toDouble() / totalCost) * 100,
            positions = positions,
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
            executionLogs = refresher.buildExecutionLogs(base.generatedDate, picks, trackRecords, emptyMap()),
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
            openPositions = openPositions, recentTrades = trades,
        )
    }

    // ─── Empty defaults (사용자 워크스페이스가 비어있을 때) ───────────────────

    private fun emptyPortfolio() = PortfolioSummary(
        totalCost = 0L, totalValue = 0L, totalProfit = 0L, totalProfitRate = 0.0, positions = emptyList(),
    )

    private fun emptyAiRecommendations() = AIRecommendationSection(
        generatedDate = LocalDate.now().toString(),
        summary = "",
        picks = emptyList(),
        trackRecords = emptyList(),
        executionLogs = emptyList(),
    )

    private fun emptyPaperTrading() = PaperTradingSummary(
        cash = 0, evaluation = 0L, totalReturnRate = 0.0,
        openPositions = emptyList(), recentTrades = emptyList(),
    )

    private fun buildQuoteUniverse(userId: UUID?): List<String> {
        return (workspaceStore.loadWatchlist(userId).asSequence().map { it.ticker } +
            workspaceStore.loadPortfolioPositions(userId).asSequence().map { it.ticker } +
            workspaceStore.loadAiTrackRecords(userId).asSequence().map { it.ticker } +
            workspaceStore.loadPaperPositions(userId).asSequence().map { it.ticker })
            .filter { it.all(Char::isDigit) }
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()
    }
}
