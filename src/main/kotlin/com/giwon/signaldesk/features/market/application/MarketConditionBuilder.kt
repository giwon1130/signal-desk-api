package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.common.KST
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

data class MarketCondition(
    val market: String,
    val asOf: String,
    val rulesVersion: String,
    val horizon: String,
    val direction: String,
    val directionLabel: String,
    val riskLevel: String,
    val riskLabel: String,
    val dataStatus: String,
    val headline: String,
    val summary: String,
    val watchPoints: List<String>,
    val evidence: List<MetricEvidence>,
)

/** Describes observed local index moves separately from the shared external-risk assessment. */
class MarketConditionBuilder(private val sessions: MarketSessionService) {
    /** Compatibility metrics only from the same validated evidence; undated flow/short chart data is omitted. */
    fun metrics(report: MarketEvidenceReport): List<SummaryMetric> {
        fun quote(id: String) = report.evidence.firstOrNull { it.id == id && it.status == "OBSERVED" }
        val vix = quote("^VIX")?.value?.let { VixSnapshot(it, 0.0, quote("^VIX")?.observedAt.orEmpty()) }
        val kr = listOfNotNull(quote("^KS11"), quote("^KQ11"))
        val us = listOfNotNull(quote("^GSPC"), quote("^IXIC"))
        return listOfNotNull(
            MarketHeatCalculator.fearMeter(vix)?.let { SummaryMetric("Fear Meter", it,
                MarketHeatCalculator.fearMeterState(vix), "VIX 기반 위험심리 · 높을수록 불안 완화 · " + quote("^VIX")!!.observedAt, "SUPPORTIVE") },
            kr.takeIf { it.size == 2 && it.all { q -> q.change?.isFinite() == true } }?.let {
                SummaryMetric("KR Heat", (50 + it.map { q -> q.change!! }.average() * 8).coerceIn(0.0, 100.0),
                    build(report, "KR").directionLabel, "한국 주요 지수 등락 강도 · 50은 보합 기준", "SUPPORTIVE") },
            us.takeIf { it.size == 2 && it.all { q -> q.change?.isFinite() == true } }?.let {
                SummaryMetric("US Heat", (50 + it.map { q -> q.change!! }.average() * 6).coerceIn(0.0, 100.0),
                    build(report, "US").directionLabel, "미국 주요 지수 등락 강도 · 50은 보합 기준", "SUPPORTIVE") },
        )
    }

    fun build(report: MarketEvidenceReport, market: String): MarketCondition {
        val ids = if (market == "KR") listOf("^KS11", "^KQ11") else listOf("^GSPC", "^IXIC")
        val relevant = ids + if (market == "KR") listOf("KRW=X", "DGS10", "^VIX", "KR_NIGHT") else listOf("DGS10", "^VIX", "CL=F")
        val evidence = report.evidence.filter { it.id in relevant }
        val cash = evidence.filter { it.id in ids && it.status == "OBSERVED" && it.change?.isFinite() == true }
        val now = Instant.parse(report.asOf)
        val regular = sessions.buildMarketSessions(now.atZone(ZoneOffset.UTC)).any { it.market == market && it.phase == "REGULAR" }
        val direction = when {
            cash.size != 2 -> "UNKNOWN"
            cash.any { it.change!! > 0 } && cash.any { it.change!! < 0 } -> "MIXED"
            cash.all { it.change!! >= .2 } -> "UP"
            cash.all { it.change!! <= -.2 } -> "DOWN"
            else -> "FLAT"
        }
        val label = when (direction) { "UP" -> "상승 우세"; "DOWN" -> "하락 우세"; "MIXED" -> "흐름 엇갈림"; "FLAT" -> "뚜렷한 방향 없음"; else -> "판단 보류" }
        val name = if (market == "KR") "한국" else "미국"
        val timing = if (regular) "현재" else "최근 거래일"
        val headline = when (direction) {
            "UP" -> if (regular) "$name 증시는 주요 지수가 함께 상승하고 있습니다." else "$name 증시는 $timing 주요 지수가 함께 상승했습니다."
            "DOWN" -> if (regular) "$name 증시는 주요 지수가 함께 하락하고 있습니다." else "$name 증시는 $timing 주요 지수가 함께 하락했습니다."
            "MIXED" -> if (regular) "$name 증시는 주요 지수의 흐름이 엇갈리고 있습니다." else "$name 증시는 $timing 주요 지수의 흐름이 엇갈렸습니다."
            "FLAT" -> "$name 증시는 $timing 뚜렷한 방향이 나타나지 않았습니다."
            else -> "$name 시장 흐름을 판단할 최신 자료가 부족합니다."
        }
        val risk = CompositeRiskService().fromReport(report)
        val summary = if (cash.size == 2) cash.joinToString(" · ") {
            it.label + " " + String.format(Locale.KOREA, "%+.2f%%", it.change)
        } + if (regular) "입니다. 지수 등락만으로 개별 종목의 흐름을 단정할 수 없습니다."
            else "로 관측된 자료입니다. 다음 개장 방향을 뜻하지 않습니다."
        else "자료가 다시 확인되면 안내합니다. 자료 부족은 보합이나 안정 상태를 뜻하지 않습니다."
        return MarketCondition(
            market, report.asOf, report.rulesVersion, if (regular) "CURRENT_SESSION" else "LATEST_OBSERVATION",
            direction, label, report.riskLevel, risk.level,
            if (direction == "UNKNOWN") "INSUFFICIENT" else if (report.riskLevel == "UNKNOWN" ||
                evidence.any { it.id != "KR_NIGHT" && it.status != "OBSERVED" }) "PARTIAL" else "AVAILABLE",
            headline, summary,
            listOf(risk.headline, if (direction == "MIXED") "시장 전체를 한 방향으로 해석하기보다 보유 종목과 업종별 흐름을 확인해 주세요."
                else "주요 일정과 보유 종목의 가격 변화를 함께 확인해 주세요."),
            evidence,
        )
    }
}
