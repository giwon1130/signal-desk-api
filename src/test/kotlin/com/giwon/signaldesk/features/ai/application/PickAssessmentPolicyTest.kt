package com.giwon.signaldesk.features.ai.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PickAssessmentPolicyTest {
    private val candidate = PickCandidate("KR", "005930", "삼성전자", 70000.0, 2.0, "외인 순매수")

    @Test
    fun `임계값 경계에서 일관되게 보류한다`() {
        mapOf(
            -10.0 to PickDecision.AVOID, -9.999 to PickDecision.WATCH,
            -3.0 to PickDecision.WATCH, -2.999 to PickDecision.REVIEW,
            5.999 to PickDecision.REVIEW, 6.0 to PickDecision.WATCH,
            9.999 to PickDecision.WATCH, 10.0 to PickDecision.AVOID,
        ).forEach { (rate, decision) ->
            assertEquals(decision, PickAssessmentPolicy.assess(candidate.copy(changeRate = rate)).decision, "rate=$rate")
        }
    }

    @Test
    fun `데이터 누락과 비정상 값은 수급으로 상쇄하지 않는다`() {
        listOf(
            candidate.copy(price = null), candidate.copy(price = Double.NaN), candidate.copy(price = -1.0),
            candidate.copy(changeRate = null), candidate.copy(changeRate = Double.NEGATIVE_INFINITY),
            candidate.copy(market = "UNKNOWN"),
        ).forEach {
            val assessment = PickAssessmentPolicy.assess(it)
            assertEquals(PickDecision.INSUFFICIENT_DATA, assessment.decision)
            assertTrue(assessment.blockers.isNotEmpty())
        }
    }

    @Test
    fun `수급 없는 하락 종목은 관찰로 남긴다`() {
        assertEquals(PickDecision.WATCH, PickAssessmentPolicy.assess(candidate.copy(changeRate = -1.0, flowTag = " ")).decision)
        assertEquals(PickDecision.REVIEW, PickAssessmentPolicy.assess(candidate.copy(market = "US", flowTag = null)).decision)
    }
}
