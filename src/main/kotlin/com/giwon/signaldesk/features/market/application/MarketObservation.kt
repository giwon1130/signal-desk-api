package com.giwon.signaldesk.features.market.application

import java.time.*
import java.util.Locale
import kotlin.math.abs

/** Observed index direction is not a weighted forecast or an external risk score. */
data class MarketObservation(
    val market: String,
    val tradingDate: String?,
    val phase: String,
    val status: String,
    val direction: String,
    val directionLabel: String,
    val headline: String,
    val summary: String,
    val observedAt: String?,
    val evidenceIds: List<String>,
)

class MarketObservationBuilder(private val sessions: MarketSessionService) {
    fun build(evidence: List<MetricEvidence>, now: Instant, market: String): MarketObservation {
        val zone = ZoneId.of(if (market == "KR") "Asia/Seoul" else "America/New_York")
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        fun trading(date: LocalDate) = if (market == "KR") sessions.isKrTradingDay(date) else sessions.isUsTradingDay(date)
        val open = if (market == "KR") LocalTime.of(9, 0) else LocalTime.of(9, 30)
        val regular = sessions.isRegularSession(market, now)
        val phase = when {
            !trading(today) -> "NON_TRADING_DAY"
            local.toLocalTime() < open -> "PREOPEN"
            regular -> "REGULAR"
            else -> "AFTER_CLOSE"
        }
        var expected = if (phase in setOf("PREOPEN", "NON_TRADING_DAY")) today.minusDays(1) else today
        while (!trading(expected)) expected = expected.minusDays(1)
        val ids = if (market == "KR") listOf("^KS11", "^KQ11") else listOf("^GSPC", "^IXIC")
        val cash = ids.mapNotNull { id -> evidence.singleOrNull { it.id == id }?.takeIf {
            it.status == "OBSERVED" && it.change?.isFinite() == true && it.value?.isFinite() == true &&
                it.observationDate == expected.toString() &&
                runCatching { Instant.parse(it.observedAt).atZone(zone).toLocalDate() == expected }.getOrDefault(false)
        } }
        val name = if (market == "KR") "국내" else "미국"
        if (cash.size != 2) return MarketObservation(market, null, phase, "UNAVAILABLE", "UNKNOWN", "판단 보류",
            "$name 시장 흐름을 판단할 최신 자료가 부족합니다",
            "주요 지수 자료를 확인 중입니다. 자료 부족은 보합이나 안정 상태를 뜻하지 않습니다.", null, ids)

        val observed = cash.minOf { Instant.parse(it.observedAt) }
        val close = sessions.upcomingSessions(expected.atStartOfDay(zone).toInstant(), 1).sessions
            .singleOrNull { it.market == market && it.tradingDate == expected.toString() }?.closesAt?.let(Instant::parse)
        // An on-date quote is not enough: pre-close ticks must never be called a closing result.
        val closed = close != null && now >= close && cash.all { Instant.parse(it.observedAt) >= close }
        val status = when { regular -> "INTRADAY"; closed -> "CLOSED"; else -> "CLOSE_PENDING" }
        val changes = cash.map { it.change!! }
        val direction = when {
            changes.all { abs(it) < .1 } -> "FLAT"
            changes.any { it > 0 } && changes.any { it < 0 } -> "MIXED"
            changes.all { it >= 0 } -> "UP"
            changes.all { it <= 0 } -> "DOWN"
            else -> "FLAT"
        }
        val sharp = changes.all { abs(it) >= 2.0 }
        val label = when (direction) {
            "UP" -> if (sharp) "큰 폭 상승" else "상승 흐름"
            "DOWN" -> if (sharp) "큰 폭 하락" else "하락 흐름"
            "MIXED" -> "지수별 혼조"
            else -> "보합권"
        }
        val movement = when (direction) {
            "UP" -> if (sharp) "큰 폭으로 상승" else "상승"
            "DOWN" -> if (sharp) "큰 폭으로 하락" else "하락"
            "MIXED" -> "지수별로 엇갈린 흐름"
            else -> "보합권 흐름"
        }
        val headline = when {
            regular -> if (direction in setOf("UP", "DOWN")) "$name 증시는 ${movement}하고 있습니다" else "$name 증시는 ${movement}을 보이고 있습니다"
            closed -> if (direction in setOf("UP", "DOWN")) "$name 증시는 $movement 마감했습니다" else "$name 증시는 ${movement}으로 마감했습니다"
            else -> "$name 증시는 ${label}입니다 · 종가 확인 중"
        }
        val numbers = cash.joinToString(" · ") { "${it.label} ${String.format(Locale.ROOT, "%+.2f%%", it.change)}" }
        val timing = observed.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))
        val timeZone = if (market == "KR") "한국시간" else "뉴욕시간"
        val summary = when (status) {
            "CLOSED" -> "$expected 거래일에 ${numbers}로 마감했습니다. 다음 거래일 방향을 뜻하지 않습니다."
            "INTRADAY" -> "$timing $timeZone 기준 ${numbers}입니다. 지연 시세가 포함될 수 있으며 최종 마감 결과는 아닙니다."
            else -> "$timing ${timeZone}까지 확인된 값은 ${numbers}입니다. 최종 종가는 아직 확인되지 않았습니다."
        }
        return MarketObservation(market, expected.toString(), phase, status, direction, label, headline, summary,
            observed.toString(), ids)
    }
}

/** Morning KR brief starts with the latest US session, never labels yesterday's KR move as today's. */
fun MarketEvidenceReport.primaryObservation(): MarketObservation? = openingReference ?: marketObservation
