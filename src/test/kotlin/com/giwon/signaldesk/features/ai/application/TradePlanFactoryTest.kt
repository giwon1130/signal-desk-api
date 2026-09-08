package com.giwon.signaldesk.features.ai.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class TradePlanFactoryTest {
    private val factory = TradePlanFactory()
    private val generatedAt = Instant.parse("2026-08-01T01:00:00Z")

    @Test
    fun `검토 계획은 고정 손익비와 30분 만료를 사용하고 실행 불가다`() {
        val candidate = PickCandidate("KR", "000660", "SK하이닉스", 200_000.0, 2.0, "외인 순매수")

        val plan = requireNotNull(factory.build(candidate, generatedAt))

        assertEquals(TradePlanRiskLevel.MEDIUM, plan.riskLevel)
        assertEquals(5, plan.maxPositionPercent)
        assertEquals(200_000.0, plan.entryLimitPrice)
        assertEquals(195_000.0, plan.stopLossPrice)
        assertEquals(210_000.0, plan.takeProfitPrice)
        assertEquals("BUY", plan.side)
        assertEquals("LIMIT", plan.orderType)
        assertEquals("KRW", plan.currency)
        assertEquals(plan.proposalId, factory.build(candidate, generatedAt)?.proposalId)
        assertNotEquals(plan.proposalId, factory.build(candidate, generatedAt.plusSeconds(1))?.proposalId)
        assertEquals(Instant.parse("2026-08-01T01:30:00Z"), plan.expiresAt)
        assertFalse(plan.executable)
    }

    @Test
    fun `가격이 없으면 실행 가능한 것처럼 보이는 계획을 만들지 않는다`() {
        val candidate = PickCandidate("KR", "000660", "SK하이닉스", null, null, "외인 순매수")

        assertEquals(null, factory.build(candidate, generatedAt))
    }

    @Test
    fun `미국 종목 가격은 센트 단위로 반올림한다`() {
        val candidate = PickCandidate("US", "NVDA", "NVIDIA", 123.456, 2.0, null)

        val plan = requireNotNull(factory.build(candidate, generatedAt))

        assertEquals("USD", plan.currency)
        assertEquals(123.46, plan.referencePrice)
        assertEquals(120.37, plan.stopLossPrice)
        assertEquals(129.63, plan.takeProfitPrice)
    }

    @Test
    fun `관찰 회피 데이터부족 후보와 비정상 가격에는 계획이 없다`() {
        val candidate = PickCandidate("KR", "000660", "SK하이닉스", 200_000.0, 2.0, "외인 순매수")
        listOf(12.0, -12.0, 6.0, -3.0, Double.NaN, Double.POSITIVE_INFINITY, null).forEach {
            assertNull(factory.build(candidate.copy(changeRate = it), generatedAt), "change=$it")
        }
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 0.01).forEach {
            assertNull(factory.build(candidate.copy(price = it), generatedAt), "price=$it")
        }
    }
}
