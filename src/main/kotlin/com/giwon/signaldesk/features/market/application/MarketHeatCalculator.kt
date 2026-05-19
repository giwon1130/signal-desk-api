package com.giwon.signaldesk.features.market.application

import kotlin.math.abs

/**
 * Market Overview의 공포/심리 지표 계산 모음.
 * MarketOverviewService에서 분리 — 순수 계산 함수만 모음.
 */
object MarketHeatCalculator {

    fun fearMeter(vix: VixSnapshot?): Double =
        (100 - ((vix?.currentPrice ?: 17.0) - 12) * 4).coerceIn(0.0, 100.0)

    fun fearMeterState(vix: VixSnapshot?): String {
        val v = vix?.currentPrice ?: return "중립"
        return when {
            v < 15 -> "낙관 우위"
            v < 20 -> "위험선호 우위"
            v < 26 -> "경계 구간"
            else -> "공포 확대"
        }
    }

    fun fearMeterNote(vix: VixSnapshot?): String {
        val v = vix?.currentPrice ?: return "VIX 실데이터 미연결 상태라 기본 공포 점수를 사용 중"
        return "공식 CBOE VIX ${"%.2f".format(v)} 기준으로 계산한 미국 시장 위험심리"
    }

    fun vixScore(vix: VixSnapshot?): Int = fearMeter(vix).toInt()

    fun vixState(vix: VixSnapshot?): String = when (fearMeterState(vix)) {
        "낙관 우위", "위험선호 우위" -> "낮음"
        "경계 구간" -> "중간"
        else -> "높음"
    }

    fun vixNote(vix: VixSnapshot?): String {
        val v = vix?.currentPrice ?: return "VIX 기본값 기반"
        val change = vix.priceChange
        val signed = if (change > 0) "+${"%.2f".format(change)}" else "%.2f".format(change)
        return "CBOE VIX ${"%.2f".format(v)} ($signed) 기준 위험심리"
    }

    fun usHeat(vix: VixSnapshot?, us: UsIndicesSnapshot?): Double {
        val avgChange = listOfNotNull(us?.nasdaq?.changeRate, us?.sp500?.changeRate)
            .average().takeIf { it.isFinite() } ?: 0.0
        val v = vix?.currentPrice ?: 20.0
        return (50 + avgChange * 6 - (v - 20) * 1.5).coerceIn(0.0, 100.0)
    }

    fun usHeatState(usHeat: Double): String = when {
        usHeat >= 65 -> "AI/빅테크 중심"
        usHeat >= 50 -> "강세 우위"
        usHeat >= 35 -> "혼조"
        else -> "약세 우위"
    }

    fun krHeat(korea: MarketSection): Double =
        (50 + korea.indices.map { it.changeRate }.average() * 8).coerceIn(0.0, 100.0)

    fun krHeatState(korea: MarketSection): String {
        val kospi = korea.indices.firstOrNull { it.label == "KOSPI" }?.changeRate ?: 0.0
        val kosdaq = korea.indices.firstOrNull { it.label == "KOSDAQ" }?.changeRate ?: 0.0
        return when {
            kospi > kosdaq && kospi >= 0 -> "코스피 강세"
            kosdaq > kospi && kosdaq >= 0 -> "코스닥 강세"
            else -> "동반 약세"
        }
    }

    fun flowBias(korea: MarketSection): Double {
        val foreign = korea.investorFlows.firstOrNull { it.investor == "외국인" }?.amountBillionWon ?: 0.0
        val institution = korea.investorFlows.firstOrNull { it.investor == "기관" }?.amountBillionWon ?: 0.0
        return (50 + ((foreign + institution) / 60)).coerceIn(0.0, 100.0)
    }

    fun flowBiasState(korea: MarketSection): String {
        val top = korea.investorFlows.maxByOrNull { abs(it.amountBillionWon) }
        return top?.let { "${it.investor} ${if (it.positive) "우위" else "매도 우위"}" } ?: "수급 중립"
    }
}
