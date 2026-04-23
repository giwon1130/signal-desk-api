package com.giwon.signaldesk.features.market.application

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 실 OHLC 캔들 데이터로부터 차트 시리즈를 만든다.
 * - dailyCandles/weeklyCandles/monthlyCandles 가 비어있으면 시뮬레이션으로 폴백
 * - latest/changeRate 는 실시간 시세이므로 마지막 캔들의 close 를 latest로 보정
 */
fun buildIndexChartPeriodsFromOhlc(
    latest: Double,
    changeRate: Double,
    dailyCandles: List<IndexCandle>,
    weeklyCandles: List<IndexCandle>,
    monthlyCandles: List<IndexCandle>,
): List<ChartPeriodSnapshot> {
    val today = LocalDate.now()

    val dailyPoints = if (dailyCandles.isNotEmpty()) {
        candlesToPoints(dailyCandles.takeLast(30), latest, "MM/dd")
    } else {
        // 폴백: 시뮬레이션
        val closes = buildHistoricalSeries(latest, 30, 0.012, today)
        val labels = closes.mapIndexed { i, _ ->
            today.minusDays((closes.lastIndex - i).toLong()).format(DateTimeFormatter.ofPattern("MM/dd"))
        }
        buildChartPoints(labels, closes, closes.firstOrNull() ?: latest)
    }

    val weeklyPoints = if (weeklyCandles.isNotEmpty()) {
        candlesToPoints(weeklyCandles.takeLast(20), latest, "MM/dd")
    } else {
        val closes = buildHistoricalSeries(latest, 20, 0.025, today)
        val labels = closes.mapIndexed { i, _ ->
            today.minusWeeks((closes.lastIndex - i).toLong()).format(DateTimeFormatter.ofPattern("MM/dd"))
        }
        buildChartPoints(labels, closes, closes.firstOrNull() ?: latest)
    }

    val monthlyPoints = if (monthlyCandles.isNotEmpty()) {
        candlesToPoints(monthlyCandles.takeLast(12), latest, "yy/MM")
    } else {
        val closes = buildHistoricalSeries(latest, 12, 0.05, today)
        val labels = closes.mapIndexed { i, _ ->
            today.minusMonths((closes.lastIndex - i).toLong()).format(DateTimeFormatter.ofPattern("yy/MM"))
        }
        buildChartPoints(labels, closes, closes.firstOrNull() ?: latest)
    }

    return listOf(
        ChartPeriodSnapshot("D", "일봉", dailyPoints, buildIndexChartStats(dailyPoints)),
        ChartPeriodSnapshot("W", "주봉", weeklyPoints, buildIndexChartStats(weeklyPoints)),
        ChartPeriodSnapshot("M", "월봉", monthlyPoints, buildIndexChartStats(monthlyPoints, changeRate, latest)),
    )
}

/** 실 OHLC 캔들 → ChartPoint. 마지막 캔들의 close는 latest로 보정해서 실시간 시세와 일치시킨다. */
private fun candlesToPoints(
    candles: List<IndexCandle>,
    latest: Double,
    labelPattern: String,
): List<ChartPoint> {
    if (candles.isEmpty()) return emptyList()
    val formatter = DateTimeFormatter.ofPattern(labelPattern)
    val srcFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    return candles.mapIndexed { idx, candle ->
        val isLast = idx == candles.lastIndex
        // 마지막 캔들은 실시간 시세로 close 보정. high/low도 latest 가 벗어나면 확장.
        val close = if (isLast) latest else candle.close
        val high = if (isLast) max(candle.high, latest) else candle.high
        val low = if (isLast) min(candle.low, latest) else candle.low
        val label = runCatching {
            LocalDate.parse(candle.date, srcFormatter).format(formatter)
        }.getOrDefault(candle.date)

        ChartPoint(
            label = label,
            value = close,
            open = candle.open,
            high = high,
            low = low,
            close = close,
            volume = candle.volume,
        )
    }
}

/**
 * 일봉(D) / 주봉(W) / 월봉(M) 세 가지 캔들 시리즈를 생성한다.
 * - 캔들 1개 = 정확히 1일/1주/1개월 OHLC
 * - 끝점이 latest 가 되도록 보정 (오늘 = 현재가)
 * - seed 고정(날짜+가격대)으로 새로고침해도 모양 일관
 *
 * baseSeries 가 충분한 길이의 실데이터라면 일봉에 우선 사용.
 */
fun buildIndexChartPeriods(
    latest: Double,
    changeRate: Double,
    baseSeries: List<Double>,
): List<ChartPeriodSnapshot> {
    val today = LocalDate.now()

    // ── 일봉: 30 거래일 ─────────────────────────────────────────────
    val dailyCloses = pickRealOrSimulate(baseSeries, latest, today, size = 30, dailyVolatility = 0.012)
    val dailyLabels = dailyCloses.mapIndexed { index, _ ->
        today.minusDays((dailyCloses.lastIndex - index).toLong())
            .format(DateTimeFormatter.ofPattern("MM/dd"))
    }
    val dailyPoints = buildChartPoints(dailyLabels, dailyCloses, dailyCloses.firstOrNull() ?: latest)

    // ── 주봉: 20 주 ────────────────────────────────────────────────
    val weeklyCloses = buildHistoricalSeries(latest, size = 20, dailyVolatility = 0.025, today)
    val weeklyLabels = weeklyCloses.mapIndexed { index, _ ->
        today.minusWeeks((weeklyCloses.lastIndex - index).toLong())
            .format(DateTimeFormatter.ofPattern("MM/dd"))
    }
    val weeklyPoints = buildChartPoints(weeklyLabels, weeklyCloses, weeklyCloses.firstOrNull() ?: latest)

    // ── 월봉: 12 개월 ──────────────────────────────────────────────
    val monthlyCloses = buildHistoricalSeries(latest, size = 12, dailyVolatility = 0.05, today)
    val monthlyLabels = monthlyCloses.mapIndexed { index, _ ->
        today.minusMonths((monthlyCloses.lastIndex - index).toLong())
            .format(DateTimeFormatter.ofPattern("yy/MM"))
    }
    val monthlyPoints = buildChartPoints(monthlyLabels, monthlyCloses, monthlyCloses.firstOrNull() ?: latest)

    return listOf(
        ChartPeriodSnapshot("D", "일봉", dailyPoints, buildIndexChartStats(dailyPoints)),
        ChartPeriodSnapshot("W", "주봉", weeklyPoints, buildIndexChartStats(weeklyPoints)),
        ChartPeriodSnapshot("M", "월봉", monthlyPoints, buildIndexChartStats(monthlyPoints, changeRate, latest)),
    )
}

/** 실데이터가 충분(>= size 의 절반)하면 그걸 쓰고, 부족하면 시뮬레이션. */
private fun pickRealOrSimulate(
    baseSeries: List<Double>,
    latest: Double,
    today: LocalDate,
    size: Int,
    dailyVolatility: Double,
): List<Double> {
    val real = baseSeries.takeLast(size)
    if (real.size >= size / 2) {
        val spread = (real.maxOrNull() ?: 0.0) - (real.minOrNull() ?: 0.0)
        if (spread >= latest * 0.002) return real
    }
    return buildHistoricalSeries(latest, size, dailyVolatility, today)
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
