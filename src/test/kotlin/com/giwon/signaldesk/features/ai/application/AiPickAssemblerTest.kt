package com.giwon.signaldesk.features.ai.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class AiPickAssemblerTest {
    private val assembler = AiPickAssembler(TradePlanFactory())
    private val now = Instant.parse("2026-09-08T01:00:00Z")
    private val candidate = PickCandidate("KR", "005930", "삼성전자", 70000.0, 2.0, "외인 순매수")
    private fun pick(ticker: String = "005930", confidence: Int = 99) = AiPick(
        "KR", ticker, "model name", "검토 의견", 99.0, confidence, "가격 확인 필요",
    )

    @Test
    fun `목록 밖 후보와 중복을 제외하고 종목 정보를 시세 원본으로 고정한다`() {
        val result = assembler.assemble(listOf(pick("5930"), pick(), pick("UNKNOWN")), listOf(candidate), now)
        assertEquals(1, result.size)
        assertEquals("삼성전자", result.single().name)
        assertEquals("005930", result.single().ticker)
        assertNull(result.single().expectedReturnRate)
        assertNotNull(result.single().tradePlan)
    }

    @Test
    fun `AI 확신도와 기대수익률은 보류를 뒤집거나 계획 가격을 바꾸지 못한다`() {
        val hot = assembler.assemble(listOf(pick()), listOf(candidate.copy(changeRate = 11.0)), now).single()
        assertEquals(PickDecision.AVOID, hot.assessment?.decision)
        assertNull(hot.tradePlan)
        val low = assembler.assemble(listOf(pick(confidence = 1)), listOf(candidate), now).single()
        val high = assembler.assemble(listOf(pick(confidence = 100)), listOf(candidate), now).single()
        assertEquals(low.tradePlan, high.tradePlan)
        assertEquals(low.assessment, high.assessment)
    }

    @Test
    fun `검토 가능 후보가 먼저 나오며 빈 결과도 허용한다`() {
        val candidates = listOf(candidate.copy(ticker = "HOT", changeRate = 12.0), candidate)
        val result = assembler.assemble(listOf(pick("HOT"), pick()), candidates, now)
        assertEquals(listOf(PickDecision.REVIEW, PickDecision.AVOID), result.map { it.assessment?.decision })
        assertTrue(assembler.assemble(emptyList(), candidates, now).isEmpty())
    }
}
