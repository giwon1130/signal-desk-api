package com.giwon.signaldesk.features.market.application

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs

/** Read-only port. No live implementation is registered until credentials and redistribution rights are verified. */
fun interface KrxNightFuturesFeed {
    fun snapshot(): KrxNightFuturesObservation?
}

data class KrxNightFuturesObservation(
    val contractCode: String,
    val lastTradingDate: LocalDate,
    val sessionStartDate: LocalDate,
    val observedAt: Instant,
    val receivedAt: Instant,
    val value: Double,
    val previousValue: Double,
    val changeRate: Double,
    val source: String,
)

/**
 * KIS H0MFCNT0 column contract, not a socket/client or trading adapter.
 * https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_futureoption/krx_ngt_futures_ccnl/krx_ngt_futures_ccnl.py
 * The wire record has a TIME but NO DATE. A collector must supply its verified session start date
 * and the exact KOSPI200 contract/expiry from the provider's instrument master; never infer a rollover.
 */
class KisNightFuturesDecoder {
    fun decode(fields: List<String>, sessionStartDate: LocalDate, receivedAt: Instant,
               expectedContract: String, lastTradingDate: LocalDate): KrxNightFuturesObservation? = runCatching {
        require(fields.size == 49 && expectedContract.isNotBlank() && fields[0] == expectedContract)
        require(sessionStartDate < lastTradingDate) // an expired contract cannot trade that evening
        val time = LocalTime.parse(fields[1], DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT))
        require(time >= LocalTime.of(18, 0) || time <= LocalTime.of(6, 0))
        val date = if (time >= LocalTime.of(18, 0)) sessionStartDate else sessionStartDate.plusDays(1)
        val observedAt = date.atTime(time).atZone(KOREA).toInstant()
        require(observedAt <= receivedAt.plusSeconds(5) && observedAt >= receivedAt.minusSeconds(120))
        val price = fields[5].toDouble()
        val delta = fields[2].toDouble()
        val change = fields[4].toDouble()
        require(price.isFinite() && delta.isFinite() && change.isFinite())
        val signedDelta = when (fields[3]) {
            "1", "2" -> abs(delta)
            "3" -> { require(delta == 0.0 && change == 0.0); 0.0 }
            "4", "5" -> -abs(delta)
            else -> error("Unknown change sign")
        }
        val previous = price - signedDelta
        require(price > 0 && previous > 0 && abs((price / previous - 1) * 100 - change) < .02)
        KrxNightFuturesObservation(expectedContract, lastTradingDate, sessionStartDate, observedAt,
            receivedAt, price, previous, change, SOURCE)
    }.getOrNull()

    companion object {
        const val SOURCE = "KIS:H0MFCNT0"
        val KOREA: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/** KRX night holidays follow the START day, not the calendar day after midnight. */
class KrxNightFuturesEvidence(private val sessions: MarketSessionService) {
    fun assess(q: KrxNightFuturesObservation?, now: Instant): MetricEvidence {
        val local = now.atZone(KisNightFuturesDecoder.KOREA)
        val time = local.toLocalTime()
        var expectedStart = if (time >= LocalTime.of(18, 0)) local.toLocalDate() else local.toLocalDate().minusDays(1)
        while (!sessions.isKrTradingDay(expectedStart)) expectedStart = expectedStart.minusDays(1)
        val start = q?.sessionStartDate?.atTime(18, 0)?.atZone(KisNightFuturesDecoder.KOREA)?.toInstant()
        val end = start?.plus(Duration.ofHours(12))
        val status = when {
            q == null -> "MISSING"
            // The repository's 2027 lunar-holiday calendar is explicitly provisional.
            q.sessionStartDate.year != 2026 || local.year != 2026 -> "UNDATED"
            q.source != KisNightFuturesDecoder.SOURCE || q.contractCode.isBlank() ||
                !q.value.isFinite() || !q.previousValue.isFinite() || !q.changeRate.isFinite() ||
                q.value <= 0 || q.previousValue <= 0 ||
                abs((q.value / q.previousValue - 1) * 100 - q.changeRate) >= .02 -> "INVALID"
            q.sessionStartDate >= q.lastTradingDate || !sessions.isKrTradingDay(q.sessionStartDate) ||
                q.observedAt < start!! || q.observedAt > end!! || q.receivedAt > now ||
                q.observedAt > q.receivedAt.plusSeconds(5) || q.observedAt < q.receivedAt.minusSeconds(120) -> "INVALID"
            q.sessionStartDate != expectedStart || Duration.between(q.observedAt, now).toHours() > 96 -> "STALE"
            time >= LocalTime.of(9, 0) && time < LocalTime.of(18, 0) -> "STALE"
            now < end && Duration.between(q.observedAt, now).toMinutes() > 90 -> "STALE"
            now >= end && q.observedAt < end.minus(Duration.ofMinutes(90)) -> "STALE"
            else -> "OBSERVED"
        }
        val detail = if (status == "OBSERVED") {
            "코스피200 야간선물(${q!!.contractCode}) ${String.format(Locale.US, "%.2f", q.value)}, " +
                "제공사 전일 기준 대비 ${String.format(Locale.US, "%+.2f", q.changeRate)}% · 관측 ${q.observedAt}"
        } else "코스피200 야간선물: ${if (status == "MISSING") "검증된 시세 피드 미연결" else "세션·계약·시각 검증 미통과($status)"} — 방향 판단에서 제외했어. EWY와 미국 선물은 야간선물 값이 아니야."
        return MetricEvidence("KR_NIGHT", "코스피200 야간선물", q?.source,
            "https://apiportal.koreainvestment.com/apiservice", q?.observedAt?.toString(),
            q?.observedAt?.atZone(KisNightFuturesDecoder.KOREA)?.toLocalDate()?.toString(), status,
            q?.value?.takeIf { it.isFinite() }, q?.changeRate?.takeIf { it.isFinite() }, "PERCENT_CHANGE", detail)
    }
}
