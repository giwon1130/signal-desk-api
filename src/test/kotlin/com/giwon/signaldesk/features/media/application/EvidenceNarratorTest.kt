package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.market.application.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class EvidenceNarratorTest {
    private val mapper = ObjectMapper()
    private val narrator = EvidenceNarrator(GeminiClient(mapper, "", "", "http://unused", "unused"), mapper)
    private val report = MarketEvidenceAnalyzer(MarketSessionService()).analyze(
        MarketEvidenceInput(Instant.parse("2026-09-09T23:30:00Z"), emptyList(), null, null))

    @Test fun `disabled Gemini still provides complete report with evidence and warnings`() {
        val result = narrator.narrate(report)
        assertThat(result.assessment).isEqualTo(report)
        assertThat(result.headline).isEqualTo(report.headline)
        assertThat(result.sentiment).isEqualTo(MediaSentiment.NEUTRAL)
        assertThat(result.keyPoints.joinToString()).contains("자료 미확보", "미연결")
    }

    @Test fun `model can only choose approved phrases with all required IDs`() {
        val ids = narrator.options(report).keys
        val valid = mapper.writeValueAsString(ids.associateWith { 1 })
        assertThat(narrator.validateSelection(valid, ids)).hasSize(ids.size)
        assertThat(narrator.validateSelection("""{"opening":"상승 확률 99%"}""", ids)).isNull()
        assertThat(narrator.validateSelection(mapper.writeValueAsString(ids.associateWith { 2 }), ids)).isNull()
        assertThat(narrator.validateSelection(mapper.writeValueAsString(ids.associateWith { 0 } + ("newFact" to 0)), ids)).isNull()
        assertThat(narrator.validateSelection("not json", ids)).isNull()
    }

    @Test fun `phrase selection cannot change verdict numbers missing data or evidence`() {
        val zero = narrator.render(report, emptyMap())
        val one = narrator.render(report, narrator.options(report).keys.associateWith { 1 })
        assertThat(one.headline).isEqualTo(zero.headline)
        assertThat(one.assessment).isEqualTo(zero.assessment)
        assertThat(one.sentiment).isEqualTo(zero.sentiment)
        assertThat(one.summary).contains("0%", "야간선물 실측은 미연결")
    }
}
