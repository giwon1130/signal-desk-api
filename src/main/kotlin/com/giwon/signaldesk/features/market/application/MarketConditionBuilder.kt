package com.giwon.signaldesk.features.market.application

import java.time.Instant
import java.time.ZoneOffset

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
        val now = Instant.parse(report.asOf)
        val regular = sessions.buildMarketSessions(now.atZone(ZoneOffset.UTC)).any { it.market == market && it.phase == "REGULAR" }
        val observation = MarketObservationBuilder(sessions).build(report.evidence, now, market)
        val direction = observation.direction
        val risk = CompositeRiskService().fromReport(report)
        return MarketCondition(
            market, report.asOf, report.rulesVersion, if (regular) "CURRENT_SESSION" else "LATEST_OBSERVATION",
            direction, observation.directionLabel, report.riskLevel, risk.level,
            if (direction == "UNKNOWN") "INSUFFICIENT" else if (report.riskLevel == "UNKNOWN" ||
                evidence.any { it.id != "KR_NIGHT" && it.status != "OBSERVED" }) "PARTIAL" else "AVAILABLE",
            observation.headline, observation.summary,
            listOf(risk.headline, if (direction == "MIXED") "시장 전체를 한 방향으로 해석하기보다 보유 종목과 업종별 흐름을 확인해 주세요."
                else "주요 일정과 보유 종목의 가격 변화를 함께 확인해 주세요."),
            evidence,
        )
    }
}
