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
        val summary = narrator.fallbackSummary(facts)
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

    @Test fun `matching fact ids and topic words cannot legitimize reversed direction or invented cause`() {
        val facts = listOf(EvidenceNarrator.NarrativeFact("driver_1_us_equity", "primary", "미국 증시는 최근 거래에서 약세를 보였습니다."))
        for (summary in listOf(
            "미국 증시는 최근 거래에서 강세를 보였으며 시장 흐름도 안정적입니다.",
            "미국 증시는 금리 인하 기대 때문에 최근 거래에서 약세를 보였습니다.",
            "미국 증시는 최근 거래에서 약세를 보였습니다. 대규모 자금이 빠져나가고 있습니다.",
        )) {
            val json = mapper.writeValueAsString(mapOf("summary" to summary, "usedFactIds" to facts.map { it.id }))
            assertThat(narrator.validateRewrite(json, facts)).describedAs(summary).isNull()
        }
    }

    @Test fun `high volatility alone does not invent geopolitical news`() {
        val facts = narrator.narrativeFacts(report.copy(regime = "RISK_CAUTION", riskLevel = "HIGH", newsEvidence = emptyList()))
        assertThat(narrator.fallbackSummary(facts)).contains("위험 지표").doesNotContain("뉴스", "전쟁")
    }
}
