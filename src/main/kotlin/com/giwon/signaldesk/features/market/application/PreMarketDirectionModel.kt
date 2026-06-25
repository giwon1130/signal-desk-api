package com.giwon.signaldesk.features.market.application

/**
 * 한국장 시작 전 "야간 방향성 미리보기" (PRO 전용).
 *
 * 야간 코스피200 선물(FUT) + 해외상장 삼성(런던 GDR/프랑크푸르트) 등락을 모아 오늘 한국장 출발 방향을
 * 가늠한다. [locked]=true 면 FREE 사용자라 값은 비공개(앱에서 블러+업그레이드 유도).
 */
data class PreMarketDirection(
    val locked: Boolean,                  // true = PRO 전용, 값 비공개
    val kospiFutures: DirectionQuote?,    // 야간 코스피200 선물
    val overseas: List<DirectionQuote>,   // 해외상장 한국주식 (삼성 런던/프랑크푸르트)
    val bias: String?,                    // RISING | NEUTRAL | FALLING
    val biasLabel: String?,               // "오늘 상승 출발 기대" 류
    val summary: String?,                 // 한 줄 요약 (선물·삼성 + 방향)
    val sessionActive: Boolean,           // 선물 야간세션 진행중(marketStatus=OPEN)
    val asOf: String?,                    // 기준 시각(ISO local)
) {
    companion object {
        /** FREE 사용자용 — 값 없이 잠금 표시만. */
        val LOCKED = PreMarketDirection(
            locked = true, kospiFutures = null, overseas = emptyList(),
            bias = null, biasLabel = null, summary = null, sessionActive = false, asOf = null,
        )

        /** 데이터 수집 전부 실패 — 카드 자체를 숨기도록 null 처리하는 대신 빈 미잠금 상태. */
        val EMPTY = PreMarketDirection(
            locked = false, kospiFutures = null, overseas = emptyList(),
            bias = null, biasLabel = null, summary = null, sessionActive = false, asOf = null,
        )
    }
}

data class DirectionQuote(
    val label: String,       // "야간 코스피200 선물", "삼성전자(런던)"
    val changeRate: Double,  // 부호 포함 등락률 %
    val value: Double,       // 현재가
)
