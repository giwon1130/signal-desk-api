package com.giwon.signaldesk.features.market.application

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Only dated provider OHLC is charted. A live quote must never overwrite an older session's candle. */
fun buildIndexChartPeriodsFromOhlc(
    latest: Double,
    changeRate: Double,
    dailyCandles: List<IndexCandle>,
    weeklyCandles: List<IndexCandle>,
    monthlyCandles: List<IndexCandle>,
): List<ChartPeriodSnapshot> = listOf(
    period("D", "일봉", dailyCandles, 90, "MM/dd"),
    period("W", "주봉", weeklyCandles, 52, "MM/dd"),
    period("M", "월봉", monthlyCandles, 36, "yy/MM"),
)

/** Undated close-only arrays cannot supply honest OHLC, volume or trading dates. */
fun buildIndexChartPeriods(latest: Double, changeRate: Double, baseSeries: List<Double>): List<ChartPeriodSnapshot> =
    buildIndexChartPeriodsFromOhlc(latest, changeRate, emptyList(), emptyList(), emptyList())

private fun period(key: String, label: String, candles: List<IndexCandle>, limit: Int, pattern: String): ChartPeriodSnapshot {
    val unique = candles.groupBy { it.date }.filterValues { it.size == 1 }.values.map { it.single() }
    val points = unique.filter { c ->
        runCatching { LocalDate.parse(c.date, DateTimeFormatter.BASIC_ISO_DATE) }.isSuccess &&
            listOf(c.open, c.high, c.low, c.close).all { it.isFinite() && it > 0 } &&
            c.high >= maxOf(c.open, c.close) && c.low <= minOf(c.open, c.close) && c.volume >= 0
    }.sortedBy { it.date }.takeLast(limit).map { c ->
        ChartPoint(LocalDate.parse(c.date, DateTimeFormatter.BASIC_ISO_DATE).format(DateTimeFormatter.ofPattern(pattern)),
            c.close, c.open, c.high, c.low, c.close, c.volume, c.date, c.provisional)
    }
    val latest = points.lastOrNull()?.close ?: 0.0
    val previous = points.getOrNull(points.lastIndex - 1)?.close
    val high = points.maxOfOrNull { it.high } ?: 0.0
    val low = points.minOfOrNull { it.low } ?: 0.0
    return ChartPeriodSnapshot(key, label, points, ChartStats(latest, high, low,
        if (previous == null) 0.0 else (latest / previous - 1) * 100,
        high - low, if (points.isEmpty()) 0L else points.map { it.volume.toDouble() }.average().roundToLong()))
}
