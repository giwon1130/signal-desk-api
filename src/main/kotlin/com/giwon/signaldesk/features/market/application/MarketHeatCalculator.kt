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

    /**
     * 한국 과열도 — 코스피 현재가가 중기추세(60일선)보다 얼마나 위로 벌어졌나(이격도) → 밸류에이션/추세 과열.
     * 당일 등락(krHeat=강도)과 다르다: 한 번 급락해도 고점권에 스트레칭돼 있으면 과열은 여전히 높다.
     * 50=60일선, 높을수록 과열(상단 스트레칭). 60일선 대비 ±10%에서 0/100 포화.
     */
    fun krOverheat(korea: MarketSection): Double {
        val disp = krDisparityPct(korea) ?: return 50.0
        return (50 + disp * 5).coerceIn(0.0, 100.0)
    }

    fun krOverheatState(korea: MarketSection): String = when {
        krOverheat(korea) >= 70 -> "과열 경계"
        krOverheat(korea) >= 58 -> "과열 주의"
        krOverheat(korea) >= 42 -> "중립권"
        else -> "과매도권"
    }

    fun krOverheatNote(korea: MarketSection): String {
        val disp = krDisparityPct(korea) ?: return "코스피 차트 데이터 대기"
        return "코스피 60일선 대비 ${"%+.1f".format(disp)}% (이격도) — 추세 대비 스트레칭"
    }

    /** 코스피 종가 시계열로 60일선 이격도(%). 데이터 부족하면 null. */
    private fun krDisparityPct(korea: MarketSection): Double? {
        val idx = korea.indices.firstOrNull { it.label == "KOSPI" } ?: korea.indices.firstOrNull() ?: return null
        val closes = idx.periods.firstOrNull { it.key == "D" }?.points?.map { it.close }?.filter { it > 0 } ?: emptyList()
        if (closes.size < 5) return null
        val ma = closes.takeLast(60).average()
        if (ma <= 0) return null
        return (closes.last() / ma - 1.0) * 100.0
    }

    fun krHeatState(korea: MarketSection): String {
        val kospi = korea.indices.firstOrNull { it.label == "KOSPI" }?.changeRate ?: 0.0
        val kosdaq = korea.indices.firstOrNull { it.label == "KOSDAQ" }?.changeRate ?: 0.0
        return when {
            kospi > kosdaq && kospi >= 0 -> "코스피 강세"
            kosdaq > kospi && kosdaq >= 0 -> "코스닥 강세"
            else -> "동반 약세"
        }
    }

    // 외국인+기관 순매수(억원)를 0~100 으로. ±9,000억 ≈ 양극단(0/100), 50=중립.
    // (이전 /60 은 ±3,000억에서 포화 — 일간 수급은 자주 그 이상이라 0/100 에 뭉개졌다.)
    fun flowBias(korea: MarketSection): Double {
        val foreign = korea.investorFlows.firstOrNull { it.investor == "외국인" }?.amountBillionWon ?: 0.0
        val institution = korea.investorFlows.firstOrNull { it.investor == "기관" }?.amountBillionWon ?: 0.0
        return (50 + ((foreign + institution) / 180)).coerceIn(0.0, 100.0)
    }

    fun flowBiasState(korea: MarketSection): String {
        val top = korea.investorFlows.maxByOrNull { abs(it.amountBillionWon) }
        return top?.let { "${it.investor} ${if (it.positive) "우위" else "매도 우위"}" } ?: "수급 중립"
    }

    /** 카드 detail — 외인/기관 실제 순매수 금액(억원)을 그대로 노출. '0' 점수가 무슨 뜻인지 바로 보이게. */
    fun flowBiasDetail(korea: MarketSection): String {
        val foreign = korea.investorFlows.firstOrNull { it.investor == "외국인" }?.amountBillionWon
        val institution = korea.investorFlows.firstOrNull { it.investor == "기관" }?.amountBillionWon
        if (foreign == null && institution == null) return "국내 KRX 수급 데이터 대기"
        val sum = (foreign ?: 0.0) + (institution ?: 0.0)
        fun won(v: Double?) = if (v == null) "—" else String.format(java.util.Locale.KOREA, "%+,.0f억", v)
        return "외인 ${won(foreign)} · 기관 ${won(institution)} (합산 ${won(sum)})"
    }
}
