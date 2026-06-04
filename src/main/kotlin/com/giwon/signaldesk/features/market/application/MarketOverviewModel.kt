package com.giwon.signaldesk.features.market.application

import java.time.Instant

// Market Overview API 응답 + 세션/거래일/캐시 핵심.
// 도메인 데이터는 MarketSectionModel / WatchPortfolioAiModel / NewsBriefingModel 로 분리.

// ─── Response Types ──────────────────────────────────────────────────────────

data class MarketSummaryResponse(
    val generatedAt: String,
    val marketStatus: String,
    val summary: String,
    val marketSummary: List<SummaryMetric>,
    val alternativeSignals: List<AlternativeSignal>,
    val compositeRisk: CompositeRiskSignal,        // 통합(호환/폴백)
    val compositeRiskKr: CompositeRiskSignal,      // 한국 투자자 관점
    val compositeRiskUs: CompositeRiskSignal,      // 미국 투자자 관점
    val watchAlerts: List<WatchAlert>,
    val marketSessions: List<MarketSessionStatus>,
    val briefing: DailyBriefing,
    val sourceNotes: List<SourceNote>,
    val workspaceCounts: WorkspaceCounts,
    val newsSentiments: List<NewsSentiment>,
    val tradingDayStatus: TradingDayStatus,
)

data class MarketSectionsResponse(
    val generatedAt: String,
    val koreaMarket: MarketSection,
    val usMarket: MarketSection,
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

// ─── Sessions / Trading Day ─────────────────────────────────────────────────

data class TradingDayStatus(
    val krOpen: Boolean,
    val usOpen: Boolean,
    val isWeekend: Boolean,
    val isHoliday: Boolean,
    val headline: String,
    val nextTradingDay: String,
    val advice: String,
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

// ─── Workspace 집계 ─────────────────────────────────────────────────────────

data class WorkspaceCounts(
    val watchlistCount: Int,
    val portfolioCount: Int,
    val aiPickCount: Int,
)

data class WorkspaceSnapshot(
    val watchlist: List<WatchItem>,
    val portfolio: PortfolioSummary,
    val aiRecommendations: AIRecommendationSection,
)

// ─── Internal Cache Types ───────────────────────────────────────────────────

internal data class CachedMarketCore(
    val createdAt: Instant,
    val generatedAt: String,
    val marketStatus: String,
    val summary: String,
    val marketSummary: List<SummaryMetric>,
    val alternativeSignals: List<AlternativeSignal>,
    val vixSnapshot: VixSnapshot?,
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
