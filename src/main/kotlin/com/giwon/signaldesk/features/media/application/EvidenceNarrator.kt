package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.market.application.EvidenceFactor
import com.giwon.signaldesk.features.market.application.MarketEvidenceReport
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.abs

/**
 * 규칙 엔진이 확정한 정성적 사실만 사용자 친화적인 한국어로 다듬는다.
 * Gemini는 방향·수치·종목을 판단하지 않으며, 검증을 통과하지 못하면 결정론적 문장으로 대체된다.
 */
@Component
class EvidenceNarrator(private val gemini: GeminiClient, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    internal data class NarrativeFact(val id: String, val role: String, val text: String)

    fun narrate(report: MarketEvidenceReport): MarketInsightAnalysis {
        val facts = narrativeFacts(report)
        val fallback = fallbackSummary(facts)
        val rewritten = if (gemini.isEnabled() && report.regime != "INSUFFICIENT_DATA") runCatching {
            val prompt = """
                당신은 확정된 시황 사실을 전달하는 한국어 문장 편집기입니다.
                아래 approvedSummaries 중 일반 투자자가 가장 자연스럽게 읽을 수 있는 문안을 하나 선택하세요.

                작성 규칙:
                - summary는 선택한 문안을 그대로 복사합니다. 문장 추가, 삭제, 바꿔쓰기를 하지 않습니다.
                - 지표가 함께 움직였다는 사실을 주가 변동의 원인으로 해석하지 않습니다.
                - 응답은 JSON 객체 하나만 반환합니다.
                - usedFactIds에는 아래 fact id를 빠짐없이 한 번씩 넣습니다.

                facts: ${mapper.writeValueAsString(facts)}
                approvedSummaries: ${mapper.writeValueAsString(approvedSummaries(facts))}
                응답 스키마: {"summary":"존댓말 시황 해설", "usedFactIds":["fact_id"]}
            """.trimIndent()
            gemini.generateText(prompt, timeoutSeconds = 15, maxOutputTokens = 512)
                ?.let { validateRewrite(it, facts) }
        }.onFailure {
            log.warn("Market narrative rewrite failed; using friendly fallback ({})", it.javaClass.simpleName)
        }.getOrNull() else null

        if (rewritten == null && gemini.isEnabled()) {
            log.info("Market narrative: deterministic friendly fallback rules={}", report.rulesVersion)
        }
        return render(report, rewritten ?: fallback)
    }

    internal fun narrativeFacts(report: MarketEvidenceReport): List<NarrativeFact> {
        if (report.regime == "INSUFFICIENT_DATA") {
            return listOf(
                NarrativeFact("data_shortage", "primary", "시황을 판단하기에 최신 자료가 충분하지 않습니다."),
                NarrativeFact("wait_for_data", "guidance", "일부 지표만으로 방향을 단정하기 어려워 시장 흐름을 조금 더 확인할 필요가 있습니다."),
            )
        }

        val directional = report.factors
            .filter { factor -> factor.score?.let { abs(it) >= .2 } == true }
            .sortedByDescending { abs(it.score!! * it.weight) }
        val primarySign = when (report.regime) {
            "SUPPORTIVE" -> 1
            "PRESSURED", "RISK_CAUTION" -> -1
            else -> 0
        }
        val primary = when {
            primarySign > 0 -> directional.filter { it.score!! > 0 }.take(2)
            primarySign < 0 -> directional.filter { it.score!! < 0 }.take(2)
            else -> directional.take(2)
        }
        val primaryIds = primary.map { it.id }.toSet()
        val counter = directional.firstOrNull { factor ->
            factor.id !in primaryIds && primary.any { it.score!! * factor.score!! < 0 }
        }

        val facts = primary.mapIndexed { index, factor ->
            NarrativeFact("driver_${index + 1}_${factor.id}", "primary", factorSentence(factor))
        }.toMutableList()
        counter?.let {
            facts += NarrativeFact("counter_${it.id}", "counter", factorSentence(it))
        }
        riskSentence(report.riskLevel)?.let {
            facts += NarrativeFact("risk_${report.riskLevel.lowercase()}", "risk", it)
        }
        if (facts.isEmpty()) {
            facts += NarrativeFact("mixed_market", "primary", "시장에 긍정적인 요인과 부담 요인이 함께 나타나 방향이 뚜렷하지 않습니다.")
        }
        return facts.take(4)
    }

    internal fun validateRewrite(json: String, facts: List<NarrativeFact>): String? = runCatching {
        val node = mapper.readTree(json)
        if (!node.isObject || node.fieldNames().asSequence().toSet() != setOf("summary", "usedFactIds")) return null
        val summary = node["summary"]?.asText()?.trim().orEmpty()
        val idsNode = node["usedFactIds"]
        if (!idsNode.isArray) return null
        val usedIds = idsNode.map { it.asText() }
        val expectedIds = facts.map { it.id }
        if (usedIds.size != expectedIds.size || usedIds.toSet() != expectedIds.toSet()) return null
        // 단어/ID가 맞아도 방향 반전이나 새 인과관계가 끼어들 수 있다. 승인된 전체 문장만 통과시킨다.
        approvedSummaries(facts).firstOrNull { it == summary }
    }.getOrNull()

    internal fun approvedSummaries(facts: List<NarrativeFact>): List<String> = listOf(
        fallbackSummary(facts),
        facts.mapIndexed { index, fact ->
            when {
                fact.role == "counter" -> "다만 ${fact.text}"
                index > 0 && fact.role == "primary" -> "또한 ${fact.text}"
                else -> fact.text
            }
        }.joinToString(" "),
    ).distinct()

    internal fun fallbackSummary(facts: List<NarrativeFact>): String = facts.joinToString(" ") { fact ->
        if (fact.role == "counter") "다만 ${fact.text}" else fact.text
    }

    internal fun render(report: MarketEvidenceReport, summary: String): MarketInsightAnalysis {
        val evidence = report.evidence.associateBy { it.id }
        val points = report.factors.map { factor ->
            "${factor.label}: ${factor.interpretation} " +
                factor.evidenceIds.mapNotNull { evidence[it] }.joinToString(" / ") { it.detail }
        } + report.evidence.filter { it.id in setOf("^VIX", "CL=F") }.map { it.detail } + report.warnings
        return MarketInsightAnalysis(
            headline = friendlyHeadline(report.regime),
            summary = summary,
            sentiment = when (report.regime) {
                "SUPPORTIVE" -> MediaSentiment.BULLISH
                "PRESSURED", "RISK_CAUTION" -> MediaSentiment.BEARISH
                else -> MediaSentiment.NEUTRAL
            },
            keyPoints = points,
            assessment = report,
        )
    }

    private fun friendlyHeadline(regime: String): String = when (regime) {
        "INSUFFICIENT_DATA" -> "시황을 판단하기에 최신 자료가 부족합니다"
        "RISK_CAUTION" -> "시장 방향보다 위험 요인을 먼저 확인할 때입니다"
        "SUPPORTIVE" -> "시장에 우호적인 흐름이 우세합니다"
        "PRESSURED" -> "시장에 부담을 주는 흐름이 우세합니다"
        else -> "긍정 요인과 부담 요인이 엇갈리고 있습니다"
    }

    private fun factorSentence(factor: EvidenceFactor): String {
        val supportive = factor.score!! >= .2
        return when (factor.id) {
            "kr_cash" -> if (supportive) "국내 증시는 비교적 강한 흐름을 보이고 있습니다."
                else "국내 증시는 전반적으로 약한 흐름을 보이고 있습니다."
            "us_equity" -> if (supportive) "미국 증시는 최근 거래에서 강세를 보였습니다."
                else "미국 증시는 최근 거래에서 약세를 보였습니다."
            "us_futures" -> if (supportive) "미국 선물은 강세를 보이고 있습니다."
                else "미국 선물은 약세를 보이고 있습니다."
            "kr_proxy" -> if (supportive) "미국 시장의 한국 관련 ETF는 최근 거래에서 강세를 보였습니다."
                else "미국 시장의 한국 관련 ETF는 최근 거래에서 약세를 보였습니다."
            "semiconductors" -> if (supportive) "확인된 반도체 관련 지표는 전반적으로 강세입니다."
                else "확인된 반도체 관련 지표는 전반적으로 약세입니다."
            "fx" -> if (supportive) "원·달러 환율은 최근 비교 시점보다 하락했습니다."
                else "원·달러 환율은 최근 비교 시점보다 상승했습니다."
            "rates" -> if (supportive) "미국 금리 지표는 주식시장의 부담을 덜어줄 수 있는 방향입니다."
                else "미국 금리 지표는 주식시장에 부담이 될 수 있는 방향입니다."
            "kr_night" -> if (supportive) "국내 야간선물은 최근 거래에서 강세를 보였습니다."
                else "국내 야간선물은 최근 거래에서 약세를 보였습니다."
            else -> if (supportive) "${factor.label}은 시장에 우호적인 흐름을 보이고 있습니다."
                else "${factor.label}은 시장에 부담이 되는 흐름을 보이고 있습니다."
        }
    }

    private fun riskSentence(riskLevel: String): String? = when (riskLevel) {
        "HIGH" -> "확인된 위험 지표에서 강한 경고 신호가 나타나 주의가 필요합니다."
        "ELEVATED" -> "확인된 위험 지표에서 주의가 필요한 신호가 나타나고 있습니다."
        "UNKNOWN" -> "위험 판단에 필요한 일부 자료가 부족해 안정적인 구간이라고 단정하기 어렵습니다."
        "NORMAL" -> "현재 확인된 변동성과 위험 지표는 과도한 불안 국면을 가리키고 있지 않습니다."
        else -> null
    }

}
