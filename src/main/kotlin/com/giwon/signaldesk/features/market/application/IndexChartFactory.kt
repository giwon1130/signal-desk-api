package com.giwon.signaldesk.features.market.application

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.abs
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

    // 1D: 실제 장중 30분봉 데이터
    val dayCloses = normalized.takeLast(12)
    val dayLabels = dayCloses.mapIndexed { index, _ ->
        val slotTime = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
            .plusMinutes((index * 30).toLong())
        "${today.format(DateTimeFormatter.ofPattern("MM/dd"))} " +
            "${slotTime.hour.toString().padStart(2, '0')}:${if (index % 2 == 0) "00" else "30"}"
    }
    val dayPoints = buildChartPoints(dayLabels, dayCloses, dayCloses.firstOrNull() ?: latest)

    // 1M: 20 거래일 합성 랜덤워크 (날짜+가격 기반 seed → 하루 동안 일관성 유지)
    val monthSeries = buildHistoricalSeries(latest, 20, dailyVolatility = 0.008, today)
    val monthLabels = monthSeries.mapIndexed { index, _ ->
        today.minusDays((monthSeries.lastIndex - index).toLong())
            .format(DateTimeFormatter.ofPattern("MM/dd"))
    }
    val monthPoints = buildChartPoints(monthLabels, monthSeries, monthSeries.firstOrNull() ?: latest)

    // 1Y: 12개월 합성 랜덤워크
    val yearSeries = buildHistoricalSeries(latest, 12, dailyVolatility = 0.028, today)
    val yearLabels = yearSeries.mapIndexed { index, _ ->
        today.minusMonths((yearSeries.lastIndex - index).toLong())
            .format(DateTimeFormatter.ofPattern("yy/MM"))
    }
    val yearPoints = buildChartPoints(yearLabels, yearSeries, yearSeries.firstOrNull() ?: latest)

    return listOf(
        ChartPeriodSnapshot("1D", "일별", dayPoints, buildIndexChartStats(dayPoints)),
        ChartPeriodSnapshot("1M", "월별", monthPoints, buildIndexChartStats(monthPoints)),
        ChartPeriodSnapshot("1Y", "연별", yearPoints, buildIndexChartStats(yearPoints, changeRate, latest)),
    )
}

/**
 * 현재 가격(latest)을 끝점으로, 역방향 랜덤워크로 과거 시계열 생성.
 * seed를 날짜 + 가격대 기반으로 고정해 하루 동안 차트 모양이 바뀌지 않도록 함.
 */
private fun buildHistoricalSeries(
    latest: Double,
    size: Int,
    dailyVolatility: Double,
    today: LocalDate,
): List<Double> {
    val seed = today.toEpochDay() * 1000L + (latest / 100).toLong()
    val rng = Random(seed)

    // 수익률 시계열 생성 (size-1개)
    val returns = (0 until size - 1).map { rng.nextGaussian() * dailyVolatility }

    // 현재가에서 역산해 과거→현재 순서로 정렬
    val series = MutableList(size) { latest }
    for (i in size - 2 downTo 0) {
        val r = returns[size - 2 - i]
        series[i] = series[i + 1] / (1.0 + r)
    }
    return series
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
    val changeRate = fallbackChangeRate
        ?: if (previous == 0.0) 0.0 else ((latest - previous) / previous) * 100
    return ChartStats(
        latest = latest,
        high = high,
        low = low,
        changeRate = changeRate,
        range = high - low,
        averageVolume = volumeAverage,
    )
}

private fun buildChartPoints(
    labels: List<String>,
    closes: List<Double>,
    fallbackOpen: Double,
): List<ChartPoint> {
    if (closes.isEmpty()) return emptyList()

    return closes.mapIndexed { index, close ->
        val open = closes.getOrNull(index - 1) ?: fallbackOpen
        val baseSwing = max(abs(close - open), close * 0.002)

        // index % n 패턴 대신, close값 + index 조합의 결정적 해시로 다양한 wick 생성
        val wickSeed = (close * 37 + index * 13).toLong().and(0x7FFFFFFF)
        val upperMult = 0.40 + ((wickSeed % 7) * 0.06)         // 0.40 ~ 0.76
        val lowerMult = 0.35 + (((wickSeed / 7) % 6) * 0.07)   // 0.35 ~ 0.70

        val high = max(open, close) + baseSwing * upperMult
        val low = min(open, close) - baseSwing * lowerMult
        val volatility = abs(close - open)
        val volume = (750_000 + (volatility * 28_000) + (close * 170)).roundToLong()
            .coerceAtLeast(40_000)

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
