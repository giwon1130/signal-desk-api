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
                당신은 투자 판단을 하는 분석가가 아니라, 확정된 시황 사실을 쉽게 풀어쓰는 한국어 문장 편집기입니다.
                아래 facts의 의미를 모두 유지해 일반 투자자가 한 번에 이해할 수 있는 2~4문장으로 정리하세요.

                작성 규칙:
                - 모든 문장은 자연스러운 한국어 존댓말(~습니다, ~입니다)로 작성합니다.
                - facts에 없는 숫자, 지표, 종목, 사건, 원인, 전망을 추가하지 않습니다.
                - 점수 계산법, 데이터 반영률, 관측 시각, 누락 처리 방식은 본문에 설명하지 않습니다.
                - 매수·매도 권유, 가격 전망, 수익률 예측, 확정적인 표현은 쓰지 않습니다.
                - primary를 먼저 설명하고 counter는 '다만'처럼 자연스럽게 연결합니다.
                - 응답은 JSON 객체 하나만 반환합니다.
                - usedFactIds에는 아래 fact id를 빠짐없이 한 번씩 넣습니다.

                facts: ${mapper.writeValueAsString(facts)}
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
        if (summary.length !in 30..420 || summary.contains(Regex("[0-9%]"))) return null
        if (FORBIDDEN_CLAIMS.containsMatchIn(summary) || INFORMAL_ENDINGS.containsMatchIn(summary)) return null
        val allowedText = facts.joinToString(" ") { it.text }
        if (UNSUPPORTED_FACT_TERMS.findAll(summary).any { it.value !in allowedText }) return null
        if (facts.any { fact -> requiredAnchors(fact.id).none { it in summary } }) return null
        val sentences = summary.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.size !in 1..4 || sentences.any { !FORMAL_ENDING.containsMatchIn(it.trim()) }) return null
        summary
    }.getOrNull()

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
            "us_equity" -> if (supportive) "미국 증시는 국내 시장에 힘을 보태는 흐름입니다."
                else "미국 증시의 약세가 국내 시장에도 부담으로 작용하고 있습니다."
            "us_futures" -> if (supportive) "미국 선물은 시장에 힘을 보태는 방향으로 움직이고 있습니다."
                else "미국 선물의 약세가 투자 심리에 부담을 주고 있습니다."
            "kr_proxy" -> if (supportive) "미국 시장의 한국 관련 ETF는 국내 증시에 우호적인 흐름을 보이고 있습니다."
                else "미국 시장의 한국 관련 ETF가 약세를 보여 국내 증시에도 부담이 되고 있습니다."
            "semiconductors" -> if (supportive) "반도체 관련 지표가 시장에 힘을 보태고 있습니다."
                else "반도체 관련 지표가 함께 약세를 보여 시장에 부담을 주고 있습니다."
            "fx" -> if (supportive) "원·달러 환율 흐름은 국내 증시의 부담을 덜어주고 있습니다."
                else "원·달러 환율 흐름이 국내 증시에 부담으로 작용하고 있습니다."
            "rates" -> if (supportive) "미국 금리 흐름은 주식시장의 부담을 덜어주고 있습니다."
                else "미국 금리 흐름이 주식시장에 부담으로 작용하고 있습니다."
            "kr_night" -> if (supportive) "국내 야간선물은 증시에 우호적인 흐름을 보이고 있습니다."
                else "국내 야간선물은 증시에 부담이 되는 흐름을 보이고 있습니다."
            else -> if (supportive) "${factor.label}은 시장에 우호적인 흐름을 보이고 있습니다."
                else "${factor.label}은 시장에 부담이 되는 흐름을 보이고 있습니다."
        }
    }

    private fun riskSentence(riskLevel: String): String? = when (riskLevel) {
        "HIGH" -> "변동성과 뉴스 흐름에서도 강한 위험 신호가 확인되어 주의가 필요합니다."
        "ELEVATED" -> "변동성이나 뉴스 흐름에서 주의가 필요한 신호가 확인되고 있습니다."
        "UNKNOWN" -> "위험 판단에 필요한 일부 자료가 부족해 안정적인 구간이라고 단정하기 어렵습니다."
        "NORMAL" -> "현재 확인된 변동성과 위험 지표는 과도한 불안 국면을 가리키고 있지 않습니다."
        else -> null
    }

    private fun requiredAnchors(id: String): List<String> = when {
        "kr_cash" in id -> listOf("국내 증시")
        "us_equity" in id -> listOf("미국 증시")
        "us_futures" in id -> listOf("미국 선물")
        "kr_proxy" in id -> listOf("한국 관련 ETF", "한국 관련 상장지수펀드")
        "semiconductors" in id -> listOf("반도체")
        "fx" in id -> listOf("환율")
        "rates" in id -> listOf("금리")
        "kr_night" in id -> listOf("야간선물")
        id.startsWith("risk_") -> listOf("위험", "변동성", "불안")
        id == "data_shortage" -> listOf("자료", "데이터")
        id == "wait_for_data" -> listOf("방향", "시장 흐름")
        else -> listOf("시장")
    }

    companion object {
        private val FORBIDDEN_CLAIMS = Regex(
            "매수|매도|목표가|손절|수익률|상승\\s*확률|반드시|확실(?:히|한)?|급등할|급락할|오를\\s*것|내릴\\s*것",
            RegexOption.IGNORE_CASE,
        )
        private val INFORMAL_ENDINGS = Regex("(?:해|야|줘|있어|없어|보여|않았어)[.!?]?(?:\\s|$)")
        private val UNSUPPORTED_FACT_TERMS = Regex("외국인|기관|거래량|실적|공시|기업|정책|전쟁|휴전|유가|원유|원자재")
        private val FORMAL_ENDING = Regex("(?:습|합|입|됩)니다[.!?]$")
    }
}
