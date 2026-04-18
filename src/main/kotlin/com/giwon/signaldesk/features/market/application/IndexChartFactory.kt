package com.giwon.signaldesk.features.market.application

import java.time.LocalDate
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
    val today = LocalDate.now()

    // 1D: 9:00 ~ 15:30 (13개 30분봉) 장중 시뮬레이션. 종가가 latest, 시가는 latest/(1+changeRate)
    // seed 고정으로 하루 동안 일관된 모양. baseSeries 가 충분히 변동성 있으면 그걸 우선 사용.
    val intradayCloses = buildIntradaySeries(latest, changeRate, baseSeries, today)
    val dayLabels = intradayCloses.mapIndexed { index, _ ->
        val totalMinutes = index * 30
        val h = (9 + totalMinutes / 60).toString().padStart(2, '0')
        val m = if (totalMinutes % 60 == 0) "00" else "30"
        "$h:$m"
    }
    val dayPoints = buildChartPoints(dayLabels, intradayCloses, intradayCloses.firstOrNull() ?: latest)

    // 1M: 20 거래일 역방향 랜덤워크 (seed = 날짜+가격대 고정 → 하루 동안 모양 일관)
    val monthSeries = buildHistoricalSeries(latest, 20, dailyVolatility = 0.015, today)
    val monthLabels = monthSeries.mapIndexed { index, _ ->
        today.minusDays((monthSeries.lastIndex - index).toLong())
            .format(DateTimeFormatter.ofPattern("MM/dd"))
    }
    val monthPoints = buildChartPoints(monthLabels, monthSeries, monthSeries.firstOrNull() ?: latest)

    // 1Y: 12개월 역방향 랜덤워크
    val yearSeries = buildHistoricalSeries(latest, 12, dailyVolatility = 0.05, today)
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
 * 13개의 30분봉 종가 시뮬레이션. 시가 → 종가 사이 자연스러운 노이즈를 실어 캔들 모양을 만듦.
 * - 끝점이 latest 가 되도록 보정
 * - 시작점은 latest / (1 + changeRate%) — 즉 어제 종가
 * - seed 고정 (날짜 + 가격대) 으로 하루 동안 모양 일관
 * - baseSeries 가 충분히 변동성 있으면(>= 0.2%) 실데이터 사용
 */
private fun buildIntradaySeries(
    latest: Double,
    changeRate: Double,
    baseSeries: List<Double>,
    today: LocalDate,
): List<Double> {
    val slots = 13
    val real = baseSeries.takeLast(slots)
    if (real.size >= 4) {
        val spread = (real.maxOrNull() ?: 0.0) - (real.minOrNull() ?: 0.0)
        if (spread >= latest * 0.002) return real
    }

    // 어제 종가에서 latest 까지 직선 드리프트 + 가우시안 노이즈
    val yesterdayClose = if (changeRate != 0.0) latest / (1.0 + changeRate / 100.0) else latest * 0.998
    val seed = today.toEpochDay() * 1000L + (latest / 100).toLong()
    val rng = Random(seed)
    val volatility = latest * 0.0015   // 30분봉 변동성

    val series = MutableList(slots) { idx ->
        val t = idx.toDouble() / (slots - 1)
        val drift = yesterdayClose + (latest - yesterdayClose) * t
        drift + rng.nextGaussian() * volatility
    }
    series[0] = yesterdayClose
    series[slots - 1] = latest
    return series
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

    val returns = (0 until size - 1).map { rng.nextGaussian() * dailyVolatility }

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
        // 최소 swing: 0.5% 보장 → 모든 캔들에 body/wick가 시각적으로 드러남
        val baseSwing = max(abs(close - open), close * 0.005)

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
