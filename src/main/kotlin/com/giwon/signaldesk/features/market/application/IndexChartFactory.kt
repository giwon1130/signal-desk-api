package com.giwon.signaldesk.features.market.application

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun buildIndexChartPeriods(
    latest: Double,
    changeRate: Double,
    baseSeries: List<Double>,
): List<ChartPeriodSnapshot> {
    val normalized = if (baseSeries.isEmpty()) listOf(latest) else baseSeries
    val today = LocalDate.now()

    val dayPoints = normalized.takeLast(12).mapIndexed { index, value ->
        val slotTime = LocalDateTime.now()
            .withHour(9)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .plusMinutes((index * 30).toLong())
        val minute = if (index % 2 == 0) "00" else "30"
        ChartPoint("${today.format(DateTimeFormatter.ofPattern("MM/dd"))} ${slotTime.hour.toString().padStart(2, '0')}:$minute", value)
    }
    val monthSeries = resampleIndexSeries(normalized, 20)
    val monthPoints = monthSeries.mapIndexed { index, value ->
        val day = today.minusDays((monthSeries.lastIndex - index).toLong())
        ChartPoint(day.format(DateTimeFormatter.ofPattern("MM/dd")), value)
    }
    val yearSeries = resampleIndexSeries(normalized, 12)
    val yearPoints = yearSeries.mapIndexed { index, value ->
        val month = today.minusMonths((yearSeries.lastIndex - index).toLong())
        ChartPoint(month.format(DateTimeFormatter.ofPattern("yy/MM")), value)
    }

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
    val values = points.map { it.value }
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
    )
}

private fun resampleIndexSeries(values: List<Double>, targetSize: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    if (values.size == 1) return List(targetSize) { values.first() }
    if (values.size == targetSize) return values

    return (0 until targetSize).map { index ->
        val sourcePosition = (index.toDouble() / (targetSize - 1)) * (values.lastIndex)
        val lowerIndex = kotlin.math.floor(sourcePosition).toInt()
        val upperIndex = kotlin.math.ceil(sourcePosition).toInt().coerceAtMost(values.lastIndex)
        val fraction = sourcePosition - lowerIndex
        val lowerValue = values[lowerIndex]
        val upperValue = values[upperIndex]
        lowerValue + ((upperValue - lowerValue) * fraction)
    }
}
