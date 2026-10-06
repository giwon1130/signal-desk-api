package com.giwon.signaldesk.features.ai.application

import java.time.Instant

/**
 * 규칙 기반 검토 후보. Gemini는 후보의 선정·판정·수익률을 결정하지 않는다.
 *  - reason: 추천 근거 (2~3문장)
 *  - expectedReturnRate: 기대 수익률 % (없으면 null)
 *  - confidence: 구버전 호환 필드(현재 0), 확신도나 상승 확률로 사용 금지
 *  - riskNote: 리스크 한 줄
 */
data class AiPick(
    val market: String,
    val ticker: String,
    val name: String,
    val reason: String,
    val expectedReturnRate: Double?,
    val confidence: Int,
    val riskNote: String,
    val changeRate: Double? = null,   // 후보의 당일 등락률 — 픽 근거 노출용 (예: +19.3%)
    val flowTag: String? = null,      // 수급 태그 — '외인 순매수' 등
    /**
     * 실제 주문이 아닌 검토용 매매 계획. 현재가가 없는 후보는 null.
     * trader 연동 전까지 executable=false 를 유지한다.
     */
    val tradePlan: TradePlan? = null,
    /** 모델 확신도와 독립적인 검토 규칙. 과거 수익으로 검증한 매수 신호가 아니다. */
    val assessment: PickAssessment? = null,
)

enum class PickDecision { REVIEW, WATCH, AVOID, INSUFFICIENT_DATA }

data class PickAssessment(
    val decision: PickDecision,
    val reasons: List<String>,
    val blockers: List<String>,
    val rulesVersion: String = "review-v1",
    val analysisDate: String? = null,
    val metrics: PickMetrics? = null,
)

data class PickMetrics(val movingAverage20: Double, val momentum20Percent: Double,
    val dailyVolatilityPercent: Double, val averageTurnover20: Double, val completedVolumeRatio: Double)

enum class TradePlanRiskLevel { LOW, MEDIUM, HIGH }

data class TradePlan(
    val proposalId: String,
    val side: String = "BUY",
    val orderType: String = "LIMIT",
    val currency: String,
    val referencePrice: Double,
    val entryLimitPrice: Double,
    val stopLossPrice: Double,
    val takeProfitPrice: Double,
    val riskLevel: TradePlanRiskLevel,
    val maxPositionPercent: Int,
    val expiresAt: Instant,
    val guardrails: List<String>,
    val executable: Boolean = false,
)

data class AiPicksResponse(
    val generatedAt: String,
    val summary: String,
    val picks: List<AiPick>,
)

/** Gemini 에 넘기는 종목 후보 — universe 밖 종목 환각 방지용. */
data class PickCandidate(
    val market: String,
    val ticker: String,
    val name: String,
    val price: Double?,
    val changeRate: Double?,
    val flowTag: String?,
)
