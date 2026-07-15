package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.common.KST
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * 한국장 시작 전 "야간 방향성" 조립 (PRO 전용 — 게이팅은 호출부 [MarketOverviewService] 에서).
 *
 * 데이터 (전부 [YahooQuoteClient], 한국장 시작 전에도 라이브로 받히는 '간밤' 대용 지표):
 *  - MSCI 한국 ETF(EWY) — 간밤 미국장에서 외국인이 본 한국. 주신호(headline).
 *  - 해외상장 삼성: 런던 GDR(SMSN.IL) + 프랑크푸르트(SSU.F) — 한국 대표주 야간 등락.
 *  - SK하이닉스 ADR(SKHY) — 나스닥에 상장된 하이닉스 자체의 간밤 등락.
 *  - 마이크론(MU) — 메모리/HBM 업종 흐름을 보완하는 보조 프록시.
 *  - S&P500 선물(ES=F) — 간밤 글로벌 위험선호.
 *
 * 왜 코스피200 야간선물(EUREX)을 직접 안 쓰나: Naver `FUT` 은 정규장(09:00~15:30) 인스트루먼트라
 * 야간 세션을 안 태운다 → 새벽엔 어제 주간 종가의 등락률이 굳어 'stale'. 안정적 공개 야간선물 피드가
 * 없어, 한국장 시작 전에 라이브로 갱신되는 위 대용 지표로 방향을 가늠한다.
 * (SK하이닉스는 2026-07 나스닥 ADR(SKHY) 상장으로 직접 야간 시세를 확인할 수 있다. ADR은 한국
 * 보통주와 가격 괴리가 날 수 있으므로, 마이크론·EWY와 함께 결측 정규화 가중으로만 반영한다.)
 *
 * 방향(bias)은 Gemini 없이 룰기반 — MSCI한국 0.35 + 런던삼성 0.25 + 하이닉스ADR 0.20 + 마이크론 0.10 + S&P선물 0.10
 * 가중(결측은 정규화). 프랑크푸르트는 거래가 얇아 표시만 하고 판정엔 안 쓴다. 라이브 시세라 quote-short(45s) 캐시.
 */
@Service
class PreMarketDirectionService(
    private val yahooQuoteClient: YahooQuoteClient,
    private val marketSessionService: MarketSessionService,
    @Value("\${signal-desk.premarket.bias-threshold:0.3}") private val biasThreshold: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Yahoo 심볼 → 표시 라벨. 첫 항목(MSCI 한국)이 headline, 라벨로 다시 식별한다. */
    private val symbols = linkedMapOf(
        "EWY" to GAUGE_LABEL,
        "SMSN.IL" to LONDON_LABEL,
        "SKHY" to HYNIX_ADR_LABEL,
        "MU" to MICRON_LABEL,
        "ES=F" to SP_FUTURES_LABEL,
        "SSU.F" to "삼성전자(프랑크푸르트)",
    )

    fun current(): PreMarketDirection {
        val asOf = ZonedDateTime.now(KST)
        val raw = runCatching { yahooQuoteClient.fetchLiveIndices(symbols) }.getOrNull().orEmpty()
        val byLabel = raw.associateBy({ it.label }, { DirectionQuote(it.label, it.changeRate, it.value) })

        // headline = MSCI 한국(간밤). overseas = 삼성·하이닉스 ADR·반도체/선물(수집된 것만, 입력 순서).
        val gauge = byLabel[GAUGE_LABEL]
        val london = byLabel[LONDON_LABEL]
        val overseas = symbols.values.filter { it != GAUGE_LABEL }.mapNotNull { byLabel[it] }

        if (gauge == null && overseas.isEmpty()) {
            log.warn("PreMarketDirection — 야간 프록시 전부 수집 실패")
            return PreMarketDirection.EMPTY
        }

        val signal = computeSignal(
            gaugeRate = gauge?.changeRate,
            londonRate = london?.changeRate,
            hynixAdrRate = byLabel[HYNIX_ADR_LABEL]?.changeRate,
            micronRate = byLabel[MICRON_LABEL]?.changeRate,
            spRate = byLabel[SP_FUTURES_LABEL]?.changeRate,
        )

        return PreMarketDirection(
            locked = false,
            kospiFutures = gauge,   // headline 슬롯 = 간밤 한국 게이지(MSCI 한국)
            overseas = overseas,
            bias = signal.bias?.name,
            biasLabel = signal.bias?.label ?: "핵심 지표 수집 부족",
            summary = buildSummary(gauge, london, byLabel[HYNIX_ADR_LABEL], signal),
            sessionActive = false,  // 대용 지표라 야간선물 라이브 세션 개념 없음
            asOf = asOf.toOffsetDateTime().toString(),
            score = signal.score,
            confidence = signal.confidence.name,
            coverage = (signal.coverage * 100).roundToInt(),
            inputCount = signal.inputCount,
        )
    }

    /** 한국 거래일 06:30~09:00 KST에만 '오늘 시초가' 예측을 노출한다. */
    internal fun isPredictionWindow(now: ZonedDateTime = ZonedDateTime.now(KST)): Boolean {
        val koreaNow = now.withZoneSameInstant(KST)
        return marketSessionService.isKrTradingDay(koreaNow.toLocalDate()) &&
            koreaNow.toLocalTime() >= PREDICTION_START && koreaNow.toLocalTime() < PREDICTION_END
    }

    enum class Bias(val label: String) {
        RISING("오늘 상승 출발 기대"),
        NEUTRAL("오늘 보합 출발 예상"),
        FALLING("오늘 하락 출발 우려"),
    }

    /**
     * MSCI한국 0.35 + 런던삼성 0.25 + 하이닉스ADR 0.20 + 마이크론 0.10 + S&P선물 0.10 가중.
     * 결측 지표는 빼고 남은 가중치로 정규화한다.
     * visibility=internal: 회귀 테스트용.
     */
    internal fun computeSignal(
        gaugeRate: Double?,
        londonRate: Double?,
        hynixAdrRate: Double?,
        micronRate: Double?,
        spRate: Double?,
    ): DirectionSignal {
        val parts = listOfNotNull(
            gaugeRate?.let { it to 0.35 },
            londonRate?.let { it to 0.25 },
            hynixAdrRate?.let { it to 0.20 },
            micronRate?.let { it to 0.10 },
            spRate?.let { it to 0.10 },
        )
        val coverage = parts.sumOf { it.second }
        if (coverage < MIN_DIRECTION_COVERAGE) {
            return DirectionSignal(null, null, coverage, parts.size, Confidence.INSUFFICIENT)
        }
        val weighted = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
        val bias = when {
            weighted >= biasThreshold -> Bias.RISING
            weighted <= -biasThreshold -> Bias.FALLING
            else -> Bias.NEUTRAL
        }
        val confidence = when {
            coverage >= 0.85 -> Confidence.HIGH
            coverage >= 0.65 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return DirectionSignal(bias, weighted, coverage, parts.size, confidence)
    }

    /** "MSCI 한국 +0.7% · 삼성(런던) +1.2% · 하이닉스 ADR +0.9% → 오늘 상승 출발 기대". */
    private fun buildSummary(gauge: DirectionQuote?, london: DirectionQuote?, hynixAdr: DirectionQuote?, signal: DirectionSignal): String {
        if (signal.bias == null) {
            return "핵심 야간 지표가 ${signal.inputCount}개만 수집돼 방향을 제시하지 않아요."
        }
        val parts = buildList {
            gauge?.let { add("MSCI 한국 ${signed(it.changeRate)}") }
            london?.let { add("삼성(런던) ${signed(it.changeRate)}") }
            hynixAdr?.let { add("하이닉스 ADR ${signed(it.changeRate)}") }
        }
        val prefix = if (parts.isEmpty()) "" else parts.joinToString(" · ") + " → "
        return prefix + signal.bias.label
    }

    /** +1.23% / -0.45% (소수 2자리, 부호 항상). */
    private fun signed(rate: Double): String {
        val s = if (rate >= 0) "+" else "-"
        return "%s%.2f%%".format(s, kotlin.math.abs(rate))
    }

    companion object {
        private val PREDICTION_START = LocalTime.of(6, 30)
        private val PREDICTION_END = LocalTime.of(9, 0)
        private const val MIN_DIRECTION_COVERAGE = 0.55
        const val GAUGE_LABEL = "MSCI 한국(간밤)"
        const val LONDON_LABEL = "삼성전자(런던)"
        const val HYNIX_ADR_LABEL = "SK하이닉스(나스닥 ADR)"
        const val MICRON_LABEL = "마이크론(SK하이닉스 가늠)"
        const val SP_FUTURES_LABEL = "S&P500 선물"
    }

    internal data class DirectionSignal(
        val bias: Bias?,
        val score: Double?,
        val coverage: Double,
        val inputCount: Int,
        val confidence: Confidence,
    )

    enum class Confidence { HIGH, MEDIUM, LOW, INSUFFICIENT }
}
