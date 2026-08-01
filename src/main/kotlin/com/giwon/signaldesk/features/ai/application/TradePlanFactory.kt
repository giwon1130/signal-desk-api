package com.giwon.signaldesk.features.ai.application

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * AI 픽을 실제 주문과 분리된 검토용 매매 계획으로 바꾼다.
 * 가격·비중은 보수적인 기본값이며 실행 직전 trader 리스크 엔진이 다시 검증해야 한다.
 */
@Component
class TradePlanFactory {

    fun build(pick: AiPick, candidate: PickCandidate, generatedAt: Instant): TradePlan? {
        val reference = candidate.price?.takeIf { it > 0.0 } ?: return null
        val changeRate = candidate.changeRate ?: 0.0
        val risk = riskLevel(pick.confidence, changeRate, candidate.flowTag)
        val maxPositionPercent = when (risk) {
            TradePlanRiskLevel.LOW -> 7
            TradePlanRiskLevel.MEDIUM -> 5
            TradePlanRiskLevel.HIGH -> 3
        }

        // 이미 많이 오른 종목은 현재가 추격 대신 1.5% 눌림 가격을 진입 상한으로 제시한다.
        val entry = if (changeRate >= 10.0) reference * 0.985 else reference
        val stopRate = when (risk) {
            TradePlanRiskLevel.LOW -> 0.03
            TradePlanRiskLevel.MEDIUM -> 0.025
            TradePlanRiskLevel.HIGH -> 0.02
        }
        val targetRate = ((pick.expectedReturnRate ?: 3.0).coerceIn(3.0, 12.0)) / 100.0
        val currency = if (candidate.market == "US") "USD" else "KRW"
        val scale = if (currency == "USD") 2 else 0
        val proposalId = UUID.nameUUIDFromBytes(
            "${candidate.market}:${candidate.ticker}:$generatedAt".toByteArray(StandardCharsets.UTF_8),
        ).toString()

        return TradePlan(
            proposalId = proposalId,
            currency = currency,
            referencePrice = rounded(reference, scale),
            entryLimitPrice = rounded(entry, scale),
            stopLossPrice = rounded(entry * (1.0 - stopRate), scale),
            takeProfitPrice = rounded(entry * (1.0 + targetRate), scale),
            riskLevel = risk,
            maxPositionPercent = maxPositionPercent,
            expiresAt = generatedAt.plus(Duration.ofMinutes(30)),
            guardrails = listOf(
                "진입 상한을 넘으면 추격 매수하지 않기",
                "한 종목 비중은 ${maxPositionPercent}% 이내로 제한",
                "주문 직전 현재가와 거래 가능 시간을 다시 확인",
                "손절 기준을 불리한 방향으로 임의 변경하지 않기",
            ),
        )
    }

    private fun riskLevel(confidence: Int, changeRate: Double, flowTag: String?): TradePlanRiskLevel = when {
        confidence < 60 || abs(changeRate) >= 10.0 -> TradePlanRiskLevel.HIGH
        confidence >= 75 && abs(changeRate) <= 5.0 && flowTag != null -> TradePlanRiskLevel.LOW
        else -> TradePlanRiskLevel.MEDIUM
    }

    private fun rounded(value: Double, scale: Int): Double =
        BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toDouble()
}
