package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.common.KST

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
    private val usQuotes: NaverGlobalQuoteClient,
) {

    fun loadMarketQuotes(userId: UUID? = null): Map<String, StockQuote> {
        val universe = buildQuoteUniverse(userId)
        return runCatching { naverFinanceQuoteClient.fetchKoreanQuotes(universe["KR"].orEmpty()) }.getOrDefault(emptyMap()) +
            runCatching { usQuotes.fetchUsQuotes(universe["US"].orEmpty()) }.getOrDefault(emptyMap())
    }

    fun buildWorkspaceCounts(userId: UUID? = null) = WorkspaceCounts(
        watchlistCount = workspaceStore.loadWatchlist(userId).size,
        portfolioCount = workspaceStore.loadPortfolioPositions(userId).size,
        aiPickCount = workspaceStore.loadAiPicks(userId).size,
    )

    fun getWatchlist(userId: UUID? = null, quotes: Map<String, StockQuote> = loadMarketQuotes(userId)): WatchlistResponse {
        val items = workspaceStore.loadWatchlist(userId).map {
            WatchItem(id = it.id, market = it.market, ticker = it.ticker, name = it.name,
                price = it.price, changeRate = it.changeRate, sector = it.sector,
                stance = it.stance, note = it.note, source = "USER",
                alertBelow = it.alertBelow, alertAbove = it.alertAbove, volumeAlert = it.volumeAlert)
        }
        return WatchlistResponse(LocalDateTime.now(KST).toString(), refresher.refreshWatchlist(items, quotes))
    }

    fun getPortfolio(userId: UUID? = null, quotes: Map<String, StockQuote> = loadMarketQuotes(userId)): PortfolioResponse {
        val userPositions = workspaceStore.loadPortfolioPositions(userId).map {
            HoldingPosition(id = it.id, market = it.market, ticker = it.ticker, name = it.name,
                buyPrice = it.buyPrice, currentPrice = it.currentPrice, quantity = it.quantity,
                profitAmount = it.profitAmount, evaluationAmount = it.evaluationAmount,
                profitRate = it.profitRate, source = "USER",
                targetPrice = it.targetPrice, stopLossPrice = it.stopLossPrice)
        }
        val merged = mergePortfolio(emptyPortfolio(), userPositions)
        return PortfolioResponse(LocalDateTime.now(KST).toString(), refresher.refreshPortfolio(merged, quotes))
    }

    fun getAiRecommendations(userId: UUID? = null, quotes: Map<String, StockQuote> = loadMarketQuotes(userId)): AiRecommendationsResponse {
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
        return AiRecommendationsResponse(LocalDateTime.now(KST).toString(), refresher.refreshAiRecommendations(merged, quotes))
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
        return PortfolioValuation.summarize(positions)
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

    // ─── Empty defaults (사용자 워크스페이스가 비어있을 때) ───────────────────

    private fun emptyPortfolio() = PortfolioSummary(
        totalCost = 0.0, totalValue = 0.0, totalProfit = 0.0, totalProfitRate = 0.0, positions = emptyList(),
    )

    private fun emptyAiRecommendations() = AIRecommendationSection(
        generatedDate = LocalDate.now(KST).toString(),
        summary = "",
        picks = emptyList(),
        trackRecords = emptyList(),
        executionLogs = emptyList(),
    )

    private fun buildQuoteUniverse(userId: UUID?): Map<String, List<String>> {
        return (workspaceStore.loadWatchlist(userId).map { it.market to it.ticker } +
            workspaceStore.loadPortfolioPositions(userId).map { it.market to it.ticker } +
            workspaceStore.loadAiTrackRecords(userId).map { it.market to it.ticker })
            .filter { it.second.isNotBlank() }.distinct().groupBy({ it.first }, { it.second.trim() })
    }
}
