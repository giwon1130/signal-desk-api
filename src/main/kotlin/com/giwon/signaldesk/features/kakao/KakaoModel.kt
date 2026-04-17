package com.giwon.signaldesk.features.kakao

// ── Kakao i 오픈빌더 Skill Request ────────────────────────────────────────

data class KakaoSkillRequest(
    val userRequest: KakaoUserRequest,
)

data class KakaoUserRequest(
    val utterance: String,
    val user: KakaoUser? = null,
)

data class KakaoUser(
    val id: String = "",
)

// ── Kakao i 오픈빌더 Skill Response ──────────────────────────────────────

data class KakaoSkillResponse(
    val version: String = "2.0",
    val template: KakaoTemplate,
)

data class KakaoTemplate(
    val outputs: List<KakaoOutput>,
)

data class KakaoOutput(
    val simpleText: KakaoSimpleText? = null,
)

data class KakaoSimpleText(
    val text: String,
)

fun kakaoText(text: String) = KakaoSkillResponse(
    template = KakaoTemplate(
        outputs = listOf(KakaoOutput(simpleText = KakaoSimpleText(text)))
    )
)

// ── 내부 모델 ─────────────────────────────────────────────────────────────

data class TradeParseResult(
    val query: String,
    val price: Int,
    val quantity: Int = 1,
)

enum class KakaoIntent {
    SAVE_TRADE,      // 종목 매수 저장
    PORTFOLIO,       // 포트폴리오 현황
    SELL_CHECK,      // 매도 시기 체크
    RECOMMEND,       // 단타 추천
    DAILY_REPORT,    // 일일 리포트
    UNKNOWN,
}

data class SellSignal(
    val ticker: String,
    val name: String,
    val buyPrice: Int,
    val currentPrice: Int,
    val profitRate: Double,
    val level: SellLevel,
    val reason: String,
)

enum class SellLevel { STRONG_SELL, CAUTION, HOLD, TAKE_PROFIT }

data class DailyAlertResult(
    val generatedAt: String,
    val sellSignals: List<SellSignal>,
    val shortTermPicks: List<ShortTermPick>,
)

data class ShortTermPick(
    val ticker: String,
    val name: String,
    val basis: String,
    val confidence: Int,
    val expectedReturnRate: Double,
)
