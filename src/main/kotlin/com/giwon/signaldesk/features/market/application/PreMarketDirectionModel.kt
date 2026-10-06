package com.giwon.signaldesk.features.market.application

/**
 * 한국장 시작 전 "야간 방향성 미리보기" (PRO 전용).
 *
 * 간밤 MSCI 한국(EWY) + 해외상장 삼성·SK하이닉스 ADR + S&P선물 등락을 모아 오늘 한국장 출발
 * 방향을 가늠한다. [locked]=true 면 FREE 사용자라 값은 비공개(앱에서 블러+업그레이드 유도).
 */
data class PreMarketDirection(
    val locked: Boolean,                  // true = PRO 전용, 값 비공개
    val kospiFutures: DirectionQuote?,    // Deprecated: always null; actual observations use nightFutures.
    val overseas: List<DirectionQuote>,   // 삼성 런던/프랑크푸르트 + S&P선물
    val bias: String?,                    // RISING | NEUTRAL | FALLING
    val biasLabel: String?,               // "오늘 상승 출발 기대" 류
    val summary: String?,                 // 한 줄 요약 (간밤지표 + 방향)
    val sessionActive: Boolean,           // (대용 지표라 항상 false)
    val asOf: String?,                    // 기준 시각(ISO local)
    val score: Double? = null,            // Uncalibrated balance [-100,100], NOT return/probability.
    val confidence: String? = null,       // HIGH | MEDIUM | LOW | INSUFFICIENT
    val coverage: Int? = null,            // 핵심 지표 가중치 커버리지(0~100)
    val inputCount: Int? = null,          // 산식에 실제 사용한 핵심 지표 수
    val status: String = "OUTSIDE_WINDOW",
    val rulesVersion: String? = null,
    val nightFutures: DirectionQuote? = null,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        /** FREE 사용자용 — 값 없이 잠금 표시만. */
        val LOCKED = PreMarketDirection(
            locked = true, kospiFutures = null, overseas = emptyList(),
            bias = null, biasLabel = null, summary = null, sessionActive = false, asOf = null,
            status = "LOCKED",
        )

        /** 데이터 수집 전부 실패 — 카드 자체를 숨기도록 null 처리하는 대신 빈 미잠금 상태. */
        val EMPTY = PreMarketDirection(
            locked = false, kospiFutures = null, overseas = emptyList(),
            bias = null, biasLabel = null, summary = null, sessionActive = false, asOf = null,
        )
    }
}

/**
 * 장전 방향성의 검증 성과. 방향을 실제로 제시한 날만 표본으로 삼고 최근 [windowSize]건을 집계한다.
 * 초기 데이터가 적을 때도 응답은 유지하되, 앱은 [evaluatedCount]를 보고 노출 시점을 결정한다.
 */
data class PreMarketForecastStats(
    val evaluatedCount: Int,
    val correctCount: Int,
    val accuracyPct: Int?,
    val windowSize: Int,
    val lastPredictionDate: String? = null,
    val lastCorrect: Boolean? = null,
    val lastActualGapRate: Double? = null,
    val status: String = "INSUFFICIENT_HISTORY",
    val minimumDisplaySamples: Int = 30,
)

data class DirectionQuote(
    val label: String,       // "MSCI 한국(간밤)", "삼성전자(런던)"
    val changeRate: Double,  // 부호 포함 등락률 %
    val value: Double,       // 현재가
    val observedAt: String? = null,
    val source: String? = null,
    val isProxy: Boolean = true,
)
