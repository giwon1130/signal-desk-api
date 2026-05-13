package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

data class TechnicalSignal(
    val rsi: Double?,
    val rsiState: String?,    // "과매수" | "중립" | "과매도"
    val ma5: Int?,
    val ma20: Int?,
    val maSignal: String?,    // "GOLDEN" | "DEAD" | "NONE"
    val week52High: Int?,
    val week52Low: Int?,
    val week52State: String?, // "신고가근접" | "신저가근접" | "중간"
)

@Service
class TechnicalIndicatorCalculator {

    fun calculate(bars: List<DailyBar>): TechnicalSignal? {
        if (bars.size < 5) return null
        val closes = bars.map { it.close }

        val rsi = calculateRsi14(closes)
        val ma5 = movingAverage(closes, 5)
        val ma20 = movingAverage(closes, 20)
        val maSignal = resolveMaSignal(closes, ma5, ma20)
        val week52High = closes.max().takeIf { closes.size >= 20 }
        val week52Low = closes.min().takeIf { closes.size >= 20 }
        val week52State = resolveWeek52State(closes.last(), week52High, week52Low)

        return TechnicalSignal(
            rsi = rsi,
            rsiState = when { rsi == null -> null; rsi >= 70 -> "과매수"; rsi <= 30 -> "과매도"; else -> "중립" },
            ma5 = ma5,
            ma20 = ma20,
            maSignal = maSignal,
            week52High = week52High,
            week52Low = week52Low,
            week52State = week52State,
        )
    }

    fun volumeRatio(bars: List<DailyBar>): Double? {
        if (bars.size < 6) return null
        val avgVolume = bars.dropLast(1).takeLast(20).map { it.volume }.average()
        if (avgVolume <= 0) return null
        return bars.last().volume / avgVolume
    }

    private fun calculateRsi14(closes: List<Int>): Double? {
        if (closes.size < 15) return null
        val changes = closes.zipWithNext { a, b -> (b - a).toDouble() }
        val last14 = changes.takeLast(14)
        val avgGain = last14.filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgLoss = last14.filter { it < 0 }.map { -it }.let { if (it.isEmpty()) 0.0 else it.average() }
        if (avgLoss == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    private fun movingAverage(closes: List<Int>, period: Int): Int? {
        if (closes.size < period) return null
        return closes.takeLast(period).average().toInt()
    }

    private fun resolveMaSignal(closes: List<Int>, ma5: Int?, ma20: Int?): String? {
        if (ma5 == null || ma20 == null || closes.size < 21) return null
        val prevMa5 = movingAverage(closes.dropLast(1), 5) ?: return "NONE"
        val prevMa20 = movingAverage(closes.dropLast(1), 20) ?: return "NONE"
        return when {
            prevMa5 <= prevMa20 && ma5 > ma20 -> "GOLDEN"
            prevMa5 >= prevMa20 && ma5 < ma20 -> "DEAD"
            else -> "NONE"
        }
    }

    private fun resolveWeek52State(current: Int, high: Int?, low: Int?): String? {
        if (high == null || low == null || high == 0) return null
        return when {
            current.toDouble() / high >= 0.98 -> "신고가근접"
            low > 0 && current.toDouble() / low <= 1.03 -> "신저가근접"
            else -> "중간"
        }
    }
}
