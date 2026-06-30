package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * 한국장 시작 전 "야간 방향성" 조립 (PRO 전용 — 게이팅은 호출부 [MarketOverviewService] 에서).
 *
 * 데이터 (전부 [YahooQuoteClient], 한국장 시작 전에도 라이브로 받히는 '간밤' 대용 지표):
 *  - MSCI 한국 ETF(EWY) — 간밤 미국장에서 외국인이 본 한국. 주신호(headline).
 *  - 해외상장 삼성: 런던 GDR(SMSN.IL) + 프랑크푸르트(SSU.F) — 한국 대표주 야간 등락.
 *  - S&P500 선물(ES=F) — 간밤 글로벌 위험선호.
 *
 * 왜 코스피200 야간선물(EUREX)을 직접 안 쓰나: Naver `FUT` 은 정규장(09:00~15:30) 인스트루먼트라
 * 야간 세션을 안 태운다 → 새벽엔 어제 주간 종가의 등락률이 굳어 'stale'. 안정적 공개 야간선물 피드가
 * 없어, 한국장 시작 전에 라이브로 갱신되는 위 대용 지표로 방향을 가늠한다.
 *
 * 방향(bias)은 Gemini 없이 룰기반 — MSCI한국 0.45 + 런던삼성 0.35 + S&P선물 0.20 가중(결측은 정규화).
 * 프랑크푸르트는 거래가 얇아 표시만 하고 판정엔 안 쓴다. 라이브 시세라 quote-short(45s) 캐시.
 */
@Service
class PreMarketDirectionService(
    private val yahooQuoteClient: YahooQuoteClient,
    @Value("\${signal-desk.premarket.bias-threshold:0.3}") private val biasThreshold: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Yahoo 심볼 → 표시 라벨. 첫 항목(MSCI 한국)이 headline, 라벨로 다시 식별한다. */
    private val symbols = linkedMapOf(
        "EWY" to GAUGE_LABEL,
        "SMSN.IL" to LONDON_LABEL,
        "ES=F" to SP_FUTURES_LABEL,
        "SSU.F" to "삼성전자(프랑크푸르트)",
    )

    @Cacheable(cacheNames = ["quote-short"], key = "'premarket-direction'", unless = "#result == null")
    fun current(): PreMarketDirection {
        val raw = runCatching { yahooQuoteClient.fetchIndices(symbols) }.getOrNull().orEmpty()
        val byLabel = raw.associateBy({ it.label }, { DirectionQuote(it.label, it.changeRate, it.value) })

        // headline = MSCI 한국(간밤). overseas = 삼성런던·S&P선물·삼성프랑크푸르트(수집된 것만, 입력 순서).
        val gauge = byLabel[GAUGE_LABEL]
        val london = byLabel[LONDON_LABEL]
        val overseas = symbols.values.filter { it != GAUGE_LABEL }.mapNotNull { byLabel[it] }

        if (gauge == null && overseas.isEmpty()) {
            log.warn("PreMarketDirection — 야간 프록시 전부 수집 실패")
            return PreMarketDirection.EMPTY
        }

        val bias = computeBias(gauge?.changeRate, london?.changeRate, byLabel[SP_FUTURES_LABEL]?.changeRate)

        return PreMarketDirection(
            locked = false,
            kospiFutures = gauge,   // headline 슬롯 = 간밤 한국 게이지(MSCI 한국)
            overseas = overseas,
            bias = bias.name,
            biasLabel = bias.label,
            summary = buildSummary(gauge, london, bias),
            sessionActive = false,  // 대용 지표라 야간선물 라이브 세션 개념 없음
            asOf = null,
        )
    }

    enum class Bias(val label: String) {
        RISING("오늘 상승 출발 기대"),
        NEUTRAL("오늘 보합 출발 예상"),
        FALLING("오늘 하락 출발 우려"),
    }

    /**
     * MSCI한국 0.45 + 런던삼성 0.35 + S&P선물 0.20 가중. 결측 지표는 빼고 남은 가중치로 정규화한다.
     * visibility=internal: 회귀 테스트용.
     */
    internal fun computeBias(gaugeRate: Double?, londonRate: Double?, spRate: Double?): Bias {
        val parts = listOfNotNull(
            gaugeRate?.let { it to 0.45 },
            londonRate?.let { it to 0.35 },
            spRate?.let { it to 0.20 },
        )
        if (parts.isEmpty()) return Bias.NEUTRAL
        val weighted = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
        return when {
            weighted >= biasThreshold -> Bias.RISING
            weighted <= -biasThreshold -> Bias.FALLING
            else -> Bias.NEUTRAL
        }
    }

    /** "MSCI 한국 +0.7% · 삼성(런던) +1.2% → 오늘 상승 출발 기대". */
    private fun buildSummary(gauge: DirectionQuote?, london: DirectionQuote?, bias: Bias): String {
        val parts = buildList {
            gauge?.let { add("MSCI 한국 ${signed(it.changeRate)}") }
            london?.let { add("삼성(런던) ${signed(it.changeRate)}") }
        }
        val prefix = if (parts.isEmpty()) "" else parts.joinToString(" · ") + " → "
        return prefix + bias.label
    }

    /** +1.23% / -0.45% (소수 2자리, 부호 항상). */
    private fun signed(rate: Double): String {
        val s = if (rate >= 0) "+" else "-"
        return "%s%.2f%%".format(s, kotlin.math.abs(rate))
    }

    companion object {
        const val GAUGE_LABEL = "MSCI 한국(간밤)"
        const val LONDON_LABEL = "삼성전자(런던)"
        const val SP_FUTURES_LABEL = "S&P500 선물"
    }
}
