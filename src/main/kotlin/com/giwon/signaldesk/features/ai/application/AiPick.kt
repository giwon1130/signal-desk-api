package com.giwon.signaldesk.features.ai.application

/**
 * Gemini 가 생성하는 단타 관점 종목 추천.
 *  - reason: 추천 근거 (2~3문장)
 *  - expectedReturnRate: 기대 수익률 % (없으면 null)
 *  - confidence: 0~100 확신도
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
    val changeRate: Double?,
    val flowTag: String?,
)
