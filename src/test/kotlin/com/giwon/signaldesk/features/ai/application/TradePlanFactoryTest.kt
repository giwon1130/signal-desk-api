package com.giwon.signaldesk.features.ai.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

class TradePlanFactoryTest {
    private val factory = TradePlanFactory()
    private val generatedAt = Instant.parse("2026-08-01T01:00:00Z")

    @Test
    fun `급등 종목은 비중을 낮추고 눌림 가격을 진입 상한으로 만든다`() {
        val pick = pick(confidence = 82, expectedReturnRate = 15.0)
        val candidate = PickCandidate("KR", "000660", "SK하이닉스", 200_000.0, 12.0, "외인 순매수")

        val plan = requireNotNull(factory.build(pick, candidate, generatedAt))

        assertEquals(TradePlanRiskLevel.HIGH, plan.riskLevel)
        assertEquals(3, plan.maxPositionPercent)
        assertEquals(197_000.0, plan.entryLimitPrice)
        assertEquals(193_060.0, plan.stopLossPrice)
        assertEquals(220_640.0, plan.takeProfitPrice)
        assertEquals(Instant.parse("2026-08-01T01:30:00Z"), plan.expiresAt)
        assertFalse(plan.executable)
    }

    @Test
    fun `가격이 없으면 실행 가능한 것처럼 보이는 계획을 만들지 않는다`() {
        val candidate = PickCandidate("KR", "000660", "SK하이닉스", null, null, "외인 순매수")

        assertEquals(null, factory.build(pick(), candidate, generatedAt))
    }

    @Test
    fun `미국 종목 가격은 센트 단위로 반올림한다`() {
        val candidate = PickCandidate("US", "NVDA", "NVIDIA", 123.456, 2.0, null)

        val plan = requireNotNull(factory.build(pick(confidence = 70, expectedReturnRate = 5.0), candidate, generatedAt))

        assertEquals("USD", plan.currency)
        assertEquals(123.46, plan.referencePrice)
        assertEquals(120.37, plan.stopLossPrice)
        assertEquals(129.63, plan.takeProfitPrice)
    }

    private fun pick(confidence: Int = 70, expectedReturnRate: Double? = 5.0) = AiPick(
        market = "KR",
        ticker = "000660",
        name = "SK하이닉스",
        reason = "수급과 모멘텀을 함께 확인했습니다.",
        expectedReturnRate = expectedReturnRate,
        confidence = confidence,
        riskNote = "변동성 주의",
    )
}
