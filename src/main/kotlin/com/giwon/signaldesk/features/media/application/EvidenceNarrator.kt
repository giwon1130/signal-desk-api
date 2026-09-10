package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.market.application.MarketEvidenceReport
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Constrained NLG: Gemini may choose approved phrasing, never supply facts, numbers or decisions.
 * Prompt-only factuality checks are insufficient: no model-written free text reaches the response.
 */
@Component
class EvidenceNarrator(private val gemini: GeminiClient, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun narrate(report: MarketEvidenceReport): MarketInsightAnalysis {
        val choices = options(report)
        val selection = if (gemini.isEnabled() && report.regime != "INSUFFICIENT_DATA") runCatching {
            val prompt = """
                너는 분석가가 아니라 한국어 문장 편집기야. 아래는 규칙 엔진이 확정한 문장들이야.
                각 id에 대해 의미가 같은 두 표현 중 읽기 자연스러운 표현의 번호(0 또는 1)만 골라.
                새 사실, 숫자, 판단, 매수/매도 권고를 쓰지 마. 문장 순서를 바꾸거나 생략하지 마.
                응답은 모든 id를 포함한 JSON 객체 하나: {"opening":0,"factor_id":1,...}
                문장 후보: ${mapper.writeValueAsString(choices)}
            """.trimIndent()
            gemini.generateText(prompt, timeoutSeconds = 15, maxOutputTokens = 512)?.let { validateSelection(it, choices.keys) }
        }.onFailure { log.warn("Market narrative selection failed; using rule text ({})", it.javaClass.simpleName) }.getOrNull() else null
        if (selection == null && gemini.isEnabled()) log.info("Market narrative: deterministic fallback rules={}", report.rulesVersion)
        return render(report, selection ?: emptyMap())
    }

    internal fun validateSelection(json: String, ids: Set<String>): Map<String, Int>? = runCatching {
        val node = mapper.readTree(json)
        if (!node.isObject || node.fieldNames().asSequence().toSet() != ids) return null
        ids.associateWith { id ->
            val value = node[id]
            if (!value.isIntegralNumber || value.intValue() !in 0..1) return null
            value.intValue()
        }
    }.getOrNull()

    internal fun options(report: MarketEvidenceReport): Map<String, List<String>> = linkedMapOf(
        "opening" to listOf(report.conclusion, "지표를 종합하면 ${report.conclusion}"),
    ) + report.factors.associate { factor ->
        factor.id to listOf("${factor.label}: ${factor.interpretation}", "${factor.label}부터 보면, ${factor.interpretation}")
    }

    internal fun render(report: MarketEvidenceReport, selection: Map<String, Int>): MarketInsightAnalysis {
        val options = options(report)
        fun phrase(id: String) = options.getValue(id)[selection[id]?.takeIf { it in 0..1 } ?: 0]
        val evidence = report.evidence.associateBy { it.id }
        val points = report.factors.map { f ->
            phrase(f.id) + " " + f.evidenceIds.mapNotNull { evidence[it] }.joinToString(" / ") { it.detail }
        } + report.evidence.filter { it.id in setOf("^VIX", "CL=F") }.map { it.detail } + report.warnings
        val riskText = when (report.riskLevel) {
            "HIGH" -> "위험 경계가 필요해."; "ELEVATED" -> "위험 요인이 관측됐어."
            "UNKNOWN" -> "위험 자료가 부족해서 안정적이라고 판단할 수 없어."
            else -> "현재 확보한 위험 지표에서는 경보 조건이 충족되지 않았어. 위험이 없다는 뜻은 아니야."
        }
        val drivers = report.factors.filter { it.score != null }
            .sortedByDescending { kotlin.math.abs(it.score!! * it.weight) }.take(3)
            .joinToString("\n") { factor ->
                val fact = factor.evidenceIds.mapNotNull { evidence[it] }
                    .firstOrNull { it.status in setOf("OBSERVED", "DELAYED") }
                phrase(factor.id) + (fact?.let {
                    " ${it.detail.substringBefore(" · ")}" +
                        if (it.status == "DELAYED") " (${it.observationDate} 공표치, 실시간 아님)." else " (${it.observationDate} 관측)."
                } ?: "")
            }
        val summary = listOf(phrase("opening"), drivers, riskText,
            "야간선물 실측은 미연결 상태야. 이 내용은 매매 지시나 수익률 예측이 아니야.")
            .filter { it.isNotBlank() }.joinToString("\n\n")
        return MarketInsightAnalysis(report.headline, summary,
            when (report.regime) {
                "SUPPORTIVE" -> MediaSentiment.BULLISH
                "PRESSURED", "RISK_CAUTION" -> MediaSentiment.BEARISH
                else -> MediaSentiment.NEUTRAL
            }, points, report)
    }
}
