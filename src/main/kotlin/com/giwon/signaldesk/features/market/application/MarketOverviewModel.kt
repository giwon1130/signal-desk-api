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
    val newsSentiments: List<NewsSentiment>,
    val tradingDayStatus: TradingDayStatus,
)

data class TradingDayStatus(
    val krOpen: Boolean,
    val usOpen: Boolean,
    val isWeekend: Boolean,
    val isHoliday: Boolean,
    val headline: String,        // 사용자에게 보여줄 한 줄 ("주말 휴장 - 다음 거래일 준비" 등)
    val nextTradingDay: String,  // "월요일 09:00" 같은 안내 문자열
    val advice: String,          // 휴장 시: "오늘은 진입 금지 - 시장 재개 전 정리만"
)

data class NewsSentiment(
    val market: String,            // "KR" / "US"
    val score: Int,                // 0~100 (50=중립)
    val label: String,             // 긍정 / 중립 / 부정
    val rationale: String,
    val positiveCount: Int,
    val negativeCount: Int,
    val neutralCount: Int,
    val highlights: List<NewsHighlight>,
)

data class NewsHighlight(
    val title: String,
    val source: String,
    val url: String,
    val tone: String,              // 긍정 / 중립 / 부정
    val publishedAt: String? = null,  // ISO-8601 (예: 2026-04-25T10:24:00Z). 없으면 null.
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
    val description: String = "",   // 지표가 무엇인지/어떤 데이터 기반인지 설명 (모달용)
    val methodology: String = "",   // 점수를 어떻게 계산하는지
    val personalImpact: String? = null,  // 내 관심/보유 종목과 연결된 한 줄 해석
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
    val impact: String,
    val publishedAt: String? = null,   // RSS pubDate → ISO-8601. 없으면 null.
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
    val metrics: RecommendationMetrics? = null,
)

data class RecommendationMetrics(
    val windowDays: Int,         // 집계 윈도우 (기본 30)
    val totalCount: Int,         // 윈도우 내 종결(실현수익률 존재) 기록 수
    val successCount: Int,       // 성공 기록 수
    val hitRate: Double,         // 0.0~1.0
    val averageReturnRate: Double, // 평균 실현 수익률 %
    val bestReturnRate: Double,   // 최고 실현 수익률 %
    val worstReturnRate: Double,  // 최저 실현 수익률 %
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
    val userStatus: String = "NEW",
    val newsUrl: String? = null,
    val newsTitle: String? = null,
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
    val userStatus: String = "NEW",
    val newsUrl: String? = null,
    val newsTitle: String? = null,
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
    val afterMarket: List<String>,
    val narrative: String = "",
    val slot: String = "INTRADAY",
    val context: BriefingContext? = null,
    val actionItems: List<BriefingAction> = emptyList(),
)

data class BriefingContext(
    val holdingPnlLabel: String?,
    val holdingPnlRate: Double?,
    val watchlistAlertCount: Int,
    val marketMood: String,
    val keyEvent: String?,
)

data class BriefingAction(
    val priority: String,
    val category: String,
    val title: String,
    val detail: String,
    val ticker: String?,
    val market: String?,
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
