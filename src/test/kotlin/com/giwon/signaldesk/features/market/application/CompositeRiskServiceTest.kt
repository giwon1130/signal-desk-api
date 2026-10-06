package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CompositeRiskServiceTest {
    private val report = MarketEvidenceAnalyzer(MarketSessionService())
        .analyze(MarketEvidenceInput(Instant.parse("2026-10-06T05:00:00Z"), emptyList(), null, null))
    @Test fun `missing data never becomes neutral numeric risk`() {
        val risk = CompositeRiskService().fromReport(report)
        assertThat(risk.score).isNull()
        assertThat(risk.score100).isNull()
        assertThat(risk.level).isEqualTo("판단 보류")
        assertThat(risk.components).isEmpty()
        assertThat(risk.asOf).isEqualTo(report.asOf)
        assertThat(risk.headline).contains("위험이 낮다는 의미는 아닙니다")
    }
    @Test fun `all risk categories retain source decision without invented probabilities`() {
        for (level in listOf("HIGH", "ELEVATED", "NORMAL", "UNKNOWN")) {
            val risk = CompositeRiskService().fromReport(report.copy(riskLevel = level))
            assertThat(risk.riskLevel).isEqualTo(level)
            assertThat(risk.score100).isNull()
            assertThat(risk.methodology).contains("실험 지표는 반영하지 않습니다")
        }
    }
}
