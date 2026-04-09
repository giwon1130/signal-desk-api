package com.giwon.signaldesk.features.market.application

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

fun buildIndexChartPeriods(
    latest: Double,
    changeRate: Double,
    baseSeries: List<Double>,
): List<ChartPeriodSnapshot> {
    val normalized = if (baseSeries.isEmpty()) listOf(latest) else baseSeries
    val today = LocalDate.now()
    val now = LocalDateTime.now()

    val dayCloses = normalized.takeLast(12)
    val dayLabels = dayCloses.mapIndexed { index, _ ->
        val slotTime = now
            .withHour(9)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .plusMinutes((index * 30).toLong())
        val minute = if (index % 2 == 0) "00" else "30"
        "${today.format(DateTimeFormatter.ofPattern("MM/dd"))} ${slotTime.hour.toString().padStart(2, '0')}:$minute"
    }
    val dayPoints = buildChartPoints(dayLabels, dayCloses, dayCloses.firstOrNull() ?: latest)

    val monthSeries = resampleIndexSeries(normalized, 20)
    val monthLabels = monthSeries.mapIndexed { index, _ ->
        val day = today.minusDays((monthSeries.lastIndex - index).toLong())
        day.format(DateTimeFormatter.ofPattern("MM/dd"))
    }
    val monthPoints = buildChartPoints(monthLabels, monthSeries, monthSeries.firstOrNull() ?: latest)

    val yearSeries = resampleIndexSeries(normalized, 12)
    val yearLabels = yearSeries.mapIndexed { index, _ ->
        val month = today.minusMonths((yearSeries.lastIndex - index).toLong())
        month.format(DateTimeFormatter.ofPattern("yy/MM"))
    }
    val yearPoints = buildChartPoints(yearLabels, yearSeries, yearSeries.firstOrNull() ?: latest)

    return listOf(
        ChartPeriodSnapshot("1D", "일별", dayPoints, buildIndexChartStats(dayPoints)),
        ChartPeriodSnapshot("1M", "월별", monthPoints, buildIndexChartStats(monthPoints)),
        ChartPeriodSnapshot("1Y", "연별", yearPoints, buildIndexChartStats(yearPoints, changeRate, latest)),
    )
}

private fun buildIndexChartStats(
    points: List<ChartPoint>,
    fallbackChangeRate: Double? = null,
    fallbackLatest: Double? = null,
): ChartStats {
    val values = points.map { it.close }
    val volumeAverage = points.map { it.volume.toDouble() }.average().roundToLong()
    val latest = values.lastOrNull() ?: fallbackLatest ?: 0.0
    val previous = values.getOrNull(values.lastIndex - 1) ?: latest
    val high = values.maxOrNull() ?: latest
    val low = values.minOrNull() ?: latest
    val changeRate = fallbackChangeRate ?: if (previous == 0.0) 0.0 else ((latest - previous) / previous) * 100
    return ChartStats(
        latest = latest,
        high = high,
        low = low,
        changeRate = changeRate,
        range = high - low,
        averageVolume = volumeAverage,
    )
}

private fun resampleIndexSeries(values: List<Double>, targetSize: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    if (values.size == 1) return List(targetSize) { values.first() }
    if (values.size == targetSize) return values

    return (0 until targetSize).map { index ->
        val sourcePosition = (index.toDouble() / (targetSize - 1)) * (values.lastIndex)
        val lowerIndex = floor(sourcePosition).toInt()
        val upperIndex = ceil(sourcePosition).toInt().coerceAtMost(values.lastIndex)
        val fraction = sourcePosition - lowerIndex
        val lowerValue = values[lowerIndex]
        val upperValue = values[upperIndex]
        lowerValue + ((upperValue - lowerValue) * fraction)
    }
}

private fun buildChartPoints(
    labels: List<String>,
    closes: List<Double>,
    fallbackOpen: Double,
): List<ChartPoint> {
    if (closes.isEmpty()) return emptyList()

    return closes.mapIndexed { index, close ->
        val open = closes.getOrNull(index - 1) ?: fallbackOpen
        val baseSwing = max(abs(close - open), close * 0.0015)
        val upperWick = baseSwing * (0.45 + ((index % 3) * 0.15))
        val lowerWick = baseSwing * (0.4 + ((index % 4) * 0.12))
        val high = max(open, close) + upperWick
        val low = min(open, close) - lowerWick
        val volatility = abs(close - open)
        val volume = (750_000 + (volatility * 28_000) + (close * 170)).roundToLong().coerceAtLeast(40_000)

        ChartPoint(
            label = labels.getOrElse(index) { index.toString() },
            value = close,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
    }
}
