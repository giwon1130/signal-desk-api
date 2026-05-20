package com.giwon.signaldesk.features.ai.application

/**
 * 숨은 시그널 — 사용자의 보유/관심 KR 종목에 실제로 잡힌 신호.
 * 한 종목에 여러 트리거가 동시에 걸릴 수 있다 (공시 + 외인 순매수 등).
 */
data class HiddenSignal(
    val market: String,
    val ticker: String,
    val name: String,
    val triggers: List<SignalTrigger>,
)

data class SignalTrigger(
    val type: String,    // DISCLOSURE | FOREIGN_BUY | INSTITUTION_BUY | SURGE | PLUNGE
    val label: String,   // "공시 2건" / "외인 순매수 3위" / "+12.3%"
    val detail: String?, // 공시 제목 등 부가 설명
)

data class HiddenSignalsResponse(
    val generatedAt: String,
    val signals: List<HiddenSignal>,
)
