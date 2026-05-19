package com.giwon.signaldesk.features.market.application

// 사용자 워크스페이스 도메인 — 관심/보유/AI 추천/모의투자.

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
    val technical: TechnicalSignal? = null,
    val volume: Long = 0L,
    val volumeRatio: Double? = null,
)

data class PortfolioSummary(
    val totalCost: Long,
    val totalValue: Long,
    val totalProfit: Long,
    val totalProfitRate: Double,
    val positions: List<HoldingPosition>,
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
    val targetPrice: Int? = null,
    val stopLossPrice: Int? = null,
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
    val windowDays: Int,
    val totalCount: Int,
    val successCount: Int,
    val hitRate: Double,
    val averageReturnRate: Double,
    val bestReturnRate: Double,
    val worstReturnRate: Double,
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
    /**
     * 진입 가이드 — 라이브 시세 기반 산출. 시세 없으면 null.
     *  · entryPrice: 추천 시점의 기준가 (= 현재가)
     *  · stopLoss:  손절 라인 (entry × (1 - 0.025), -2.5% 고정)
     *  · takeProfit: 목표가 (entry × (1 + expectedReturnRate/100), 3~20% 클램프)
     */
    val entryPrice: Int? = null,
    val stopLoss: Int? = null,
    val takeProfit: Int? = null,
)

data class PaperTradingSummary(
    val cash: Int,
    val evaluation: Long,
    val totalReturnRate: Double,
    val openPositions: List<PaperPosition>,
    val recentTrades: List<PaperTrade>,
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
