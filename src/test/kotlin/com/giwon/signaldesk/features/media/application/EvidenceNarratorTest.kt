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
        assertThat(result.headline).endsWith("니다")
        assertThat(result.summary).contains("최신 자료가 충분하지 않습니다", "확인할 필요가 있습니다")
        assertThat(result.summary).doesNotContain("유효 입력", "관측", "미연결")
        assertThat(result.sentiment).isEqualTo(MediaSentiment.NEUTRAL)
        assertThat(result.keyPoints.joinToString()).contains("자료 미확보", "미연결")
    }

    @Test fun `model rewrite must use every approved fact and formal language`() {
        val facts = narrator.narrativeFacts(report)
        val summary = "시황을 판단하기에 최신 자료가 충분하지 않습니다. 일부 지표만으로 방향을 단정하기 어려워 시장 흐름을 확인할 필요가 있습니다."
        val valid = mapper.writeValueAsString(mapOf("summary" to summary, "usedFactIds" to facts.map { it.id }))
        assertThat(narrator.validateRewrite(valid, facts)).isEqualTo(summary)
        assertThat(narrator.validateRewrite(
            mapper.writeValueAsString(mapOf("summary" to "코스피가 20% 상승합니다.", "usedFactIds" to facts.map { it.id })),
            facts,
        )).isNull()
        assertThat(narrator.validateRewrite(
            mapper.writeValueAsString(mapOf("summary" to "최신 자료가 부족해.", "usedFactIds" to facts.map { it.id })),
            facts,
        )).isNull()
        assertThat(narrator.validateRewrite(
            mapper.writeValueAsString(mapOf("summary" to summary, "usedFactIds" to facts.drop(1).map { it.id })),
            facts,
        )).isNull()
        assertThat(narrator.validateRewrite("not json", facts)).isNull()
    }

    @Test fun `friendly narrative separates explanation from technical evidence`() {
        val result = narrator.render(report, narrator.fallbackSummary(narrator.narrativeFacts(report)))
        assertThat(result.summary).doesNotContain("0%", "누락 지표", "야간선물 실측")
        assertThat(result.keyPoints.joinToString()).contains("관측", "야간선물")
        assertThat(result.assessment).isEqualTo(report)
    }
}
