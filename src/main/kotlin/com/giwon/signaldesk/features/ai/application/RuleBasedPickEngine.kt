package com.giwon.signaldesk.features.ai.application

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

data class PickDailyObservation(val date: LocalDate, val close: Double, val volume: Long)

/** Transparent screening defaults, not calibrated probabilities or a validated trading strategy. */
object RuleBasedPickEngine {
    const val VERSION = "daily-trend-liquidity-v1"

    fun assess(candidate: PickCandidate, history: List<PickDailyObservation>, expectedSession: LocalDate): PickAssessment {
        fun missing(reason: String) = PickAssessment(PickDecision.INSUFFICIENT_DATA, emptyList(), listOf(reason), VERSION)
        if (candidate.market !in setOf("KR", "US")) return missing("지원 시장을 확인할 수 없습니다.")
        val eligible = history.filter { it.date <= expectedSession }
        if (eligible.groupBy { it.date }.any { it.value.size > 1 }) return missing("거래일이 중복되어 분석을 보류합니다.")
        val bars = eligible.sortedBy { it.date }.takeLast(21)
        if (bars.size < 21 || bars.last().date != expectedSession) return missing("최근 완료 거래일까지 21개 일봉이 필요합니다.")
        if (bars.first().date < expectedSession.minusDays(60)) return missing("거래 이력의 간격이 길어 분석을 보류합니다.")
        if (bars.any { !it.close.isFinite() || it.close <= 0 || it.volume <= 0 }) return missing("가격 또는 거래량 자료가 불완전합니다.")
        val returns = bars.zipWithNext { a, b -> b.close / a.close - 1 }
        if (returns.any { !it.isFinite() || abs(it) > .35 }) return missing("급변 또는 주식 분할 영향을 확인한 후 분석합니다.")
        val latest = bars.last()
        val prior = bars.dropLast(1)
        val sma = bars.takeLast(20).map { it.close }.average()
        val momentum = (latest.close / bars.first().close - 1) * 100
        val mean = returns.average()
        val volatility = sqrt(returns.map { (it - mean) * (it - mean) }.average()) * 100
        val turnover = bars.takeLast(20).map { it.close * it.volume }.average()
        val volumeRatio = latest.volume / prior.map { it.volume.toDouble() }.average()
        if (listOf(sma, momentum, volatility, turnover, volumeRatio).any { !it.isFinite() }) return missing("계산 가능한 범위의 자료가 아닙니다.")
        val minTurnover = if (candidate.market == "US") 10_000_000.0 else 1_000_000_000.0
        val dayChange = returns.last() * 100
        val blockers = buildList {
            if (turnover < minTurnover) add("최근 거래대금이 기준보다 적어 유동성을 더 확인해야 합니다.")
            if (latest.close < sma || momentum <= 0) add("최근 상승 흐름이 충분히 확인되지 않아 관찰을 이어갑니다.")
            if (volumeRatio < 1.0) add("최근 완료 거래일의 거래량이 이전 20일 평균보다 적습니다.")
            if (volatility > 4.0) add("최근 일간 변동성이 높습니다.")
            if (dayChange >= 6 || dayChange <= -3) add("최근 완료 거래일의 급등락으로 추격 진입을 보류합니다.")
            if (latest.close / sma > 1.12) add("20일 평균 가격과의 간격이 커 진입을 서두르지 않습니다.")
        }
        val reasons = listOf(
            if (latest.close >= sma) "최근 종가는 20일 평균 가격 위에 있습니다." else "최근 종가는 20일 평균 가격 아래에 있습니다.",
            "20거래일 등락률 ${format(momentum)}%, 거래량은 이전 20일 평균의 ${format(volumeRatio)}배입니다.",
            "완료된 거래일 기준 분석이며 장중 매수 신호나 상승 확률이 아닙니다.",
        )
        return PickAssessment(
            if (abs(dayChange) >= 10 || volatility > 6) PickDecision.AVOID else if (blockers.isEmpty()) PickDecision.REVIEW else PickDecision.WATCH,
            reasons, blockers, VERSION, latest.date.toString(),
            PickMetrics(sma, momentum, volatility, turnover, volumeRatio),
        )
    }

    private fun format(value: Double) = String.format(java.util.Locale.ROOT, "%.2f", value)
}
