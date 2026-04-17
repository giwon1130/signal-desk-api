package com.giwon.signaldesk.features.market.application

import java.time.Instant

// ─── Response Types ──────────────────────────────────────────────────────────

data class MarketOverviewResponse(
    val generatedAt: String,
    val marketStatus: String,
    val summary: String,
    val marketSummary: List<SummaryMetric>,
    val alternativeSignals: List<AlternativeSignal>,
    val watchAlerts: List<WatchAlert>,
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
    val watchAlerts: List<WatchAlert>,
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

// ─── Domain Types ─────────────────────────────────────────────────────────────

data class WorkspaceCounts(
    val watchlistCount: Int,
    val portfolioCount: Int,
    val paperPositionCount: Int,
    val aiPickCount: Int,
)

data class WatchAlert(
    val severity: String,
    val category: String,
    val market: String,
    val ticker: String,
    val name: String,
    val title: String,
    val note: String,
    val score: Int,
    val tags: List<String>,
)

data class SummaryMetric(
    val label: String,
    val score: Double,
    val state: String,
    val note: String
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
    val totalCost: Long,
    val totalValue: Long,
    val totalProfit: Long,
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
    val profitAmount: Long,
    val evaluationAmount: Long,
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
    val evaluation: Long,
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

// ─── Internal Cache Types ─────────────────────────────────────────────────────

internal data class CachedMarketCore(
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

internal data class CachedNewsSection(
    val createdAt: Instant,
    val generatedAt: String,
    val news: List<MarketNews>,
)

data class WorkspaceSnapshot(
    val watchlist: List<WatchItem>,
    val portfolio: PortfolioSummary,
    val aiRecommendations: AIRecommendationSection,
)
