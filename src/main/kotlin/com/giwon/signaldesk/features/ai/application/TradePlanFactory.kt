package com.giwon.signaldesk.features.ai.application

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * AI 픽을 실제 주문과 분리된 검토용 매매 계획으로 바꾼다.
 * 가격·비중은 보수적인 기본값이며 실행 직전 trader 리스크 엔진이 다시 검증해야 한다.
 */
@Component
class TradePlanFactory {

    fun build(candidate: PickCandidate, generatedAt: Instant): TradePlan? {
        if (PickAssessmentPolicy.assess(candidate).decision != PickDecision.REVIEW) return null
        val reference = requireNotNull(candidate.price)
        // 변동성 이력 없이 '저위험'이라고 단정하지 않는다. 모델이 생성한 수익률은 가격 산정에 사용하지 않는다.
        val risk = TradePlanRiskLevel.MEDIUM
        val maxPositionPercent = 5
        val entry = reference
        val stopRate = 0.025
        val targetRate = stopRate * 2
        val currency = if (candidate.market == "US") "USD" else "KRW"
        val scale = if (currency == "USD") 2 else 0
        val entryPrice = rounded(entry, scale)
        if (!(entryPrice * (1.0 + targetRate)).isFinite()) return null
        val stopPrice = rounded(entryPrice * (1.0 - stopRate), scale)
        val targetPrice = rounded(entryPrice * (1.0 + targetRate), scale)
        if (!targetPrice.isFinite() || stopPrice <= 0 || stopPrice >= entryPrice || targetPrice <= entryPrice) return null
        val proposalId = UUID.nameUUIDFromBytes(
            "${candidate.market}:${candidate.ticker}:$generatedAt".toByteArray(StandardCharsets.UTF_8),
        ).toString()

        return TradePlan(
            proposalId = proposalId,
            currency = currency,
            referencePrice = rounded(reference, scale),
            entryLimitPrice = entryPrice,
            stopLossPrice = stopPrice,
            takeProfitPrice = targetPrice,
            riskLevel = risk,
            maxPositionPercent = maxPositionPercent,
            expiresAt = generatedAt.plus(Duration.ofMinutes(30)),
            guardrails = listOf(
                "손절 2.5%·목표 5%의 예시 시나리오이며 예상 수익률이 아니야",
                "진입 상한을 넘으면 추격 매수하지 않기",
                "한 종목 비중은 ${maxPositionPercent}% 이내로 제한",
                "주문 직전 시세 신선도·장 시간·호가 단위·거래 비용을 다시 확인",
                "과거 변동성과 거래량은 이 검토 규칙에 반영되지 않았어",
                "손절 기준을 불리한 방향으로 임의 변경하지 않기",
            ),
        )
    }

    private fun rounded(value: Double, scale: Int): Double =
        BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toDouble()
}
