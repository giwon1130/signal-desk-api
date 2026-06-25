package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import kotlin.math.abs

/**
 * 한국장 시작 전 "야간 방향성" 조립 (PRO 전용 — 게이팅은 호출부 [MarketOverviewService] 에서).
 *
 * 데이터:
 *  - 야간 코스피200 선물(FUT) 라이브 등락 → [NaverIndexQuoteClient]
 *  - 해외상장 삼성: 런던 GDR(SMSN.IL, 주신호) + 프랑크푸르트(SSU.F, 옵션) → [YahooQuoteClient] 재사용
 *
 * 방향(bias)은 Gemini 없이 룰기반 — 선물(가중 0.6) + 런던 삼성(가중 0.4)의 가중 등락률로 판정.
 * 프랑크푸르트는 거래가 얇아 노이즈가 커서 표시만 하고 판정엔 안 쓴다.
 * 라이브 시세라 quote-short(45s) 캐시.
 */
@Service
class PreMarketDirectionService(
    private val naverIndexQuoteClient: NaverIndexQuoteClient,
    private val yahooQuoteClient: YahooQuoteClient,
    @Value("\${signal-desk.premarket.futures-code:FUT}") private val futuresCode: String,
    @Value("\${signal-desk.premarket.bias-threshold:0.3}") private val biasThreshold: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Yahoo 심볼 → 표시 라벨. 첫 항목(런던 GDR)이 방향 판정의 주신호. */
    private val overseasSymbols = linkedMapOf(
        "SMSN.IL" to "삼성전자(런던)",
        "SSU.F" to "삼성전자(프랑크푸르트)",
    )

    @Cacheable(cacheNames = ["quote-short"], key = "'premarket-direction'", unless = "#result == null")
    fun current(): PreMarketDirection {
        val futuresQuote = runCatching { naverIndexQuoteClient.fetchQuote(futuresCode) }.getOrNull()
        val overseasRaw = runCatching { yahooQuoteClient.fetchIndices(overseasSymbols) }.getOrNull().orEmpty()

        val futures = futuresQuote?.let {
            DirectionQuote(label = "야간 코스피200 선물", changeRate = it.changeRate, value = it.price)
        }
        val overseas = overseasRaw.map { DirectionQuote(label = it.label, changeRate = it.changeRate, value = it.value) }

        if (futures == null && overseas.isEmpty()) {
            log.warn("PreMarketDirection — 선물·해외삼성 모두 수집 실패")
            return PreMarketDirection.EMPTY
        }

        // 주신호 = 런던 GDR(라벨에 "런던"). 가중: 선물 0.6 + 런던삼성 0.4. 한쪽만 있으면 그쪽 100%.
        val londonSamsung = overseas.firstOrNull { it.label.contains("런던") }
        val bias = computeBias(futures?.changeRate, londonSamsung?.changeRate)

        return PreMarketDirection(
            locked = false,
            kospiFutures = futures,
            overseas = overseas,
            bias = bias.name,
            biasLabel = bias.label,
            summary = buildSummary(futures, overseas, bias),
            sessionActive = futuresQuote?.marketStatus.equals("OPEN", ignoreCase = true),
            asOf = futuresQuote?.tradedAt?.ifBlank { null },
        )
    }

    enum class Bias(val label: String) {
        RISING("오늘 상승 출발 기대"),
        NEUTRAL("오늘 보합 출발 예상"),
        FALLING("오늘 하락 출발 우려"),
    }

    /** 선물 0.6 + 런던삼성 0.4 가중. 한쪽만 있으면 그쪽 100%. visibility=internal: 회귀 테스트용. */
    internal fun computeBias(futuresRate: Double?, londonRate: Double?): Bias {
        val weighted = when {
            futuresRate != null && londonRate != null -> futuresRate * 0.6 + londonRate * 0.4
            futuresRate != null -> futuresRate
            londonRate != null -> londonRate
            else -> 0.0
        }
        return when {
            weighted >= biasThreshold -> Bias.RISING
            weighted <= -biasThreshold -> Bias.FALLING
            else -> Bias.NEUTRAL
        }
    }

    /** "야간 코스피선물 +0.7% · 삼성(런던) +1.2% → 오늘 상승 출발 기대". */
    private fun buildSummary(futures: DirectionQuote?, overseas: List<DirectionQuote>, bias: Bias): String {
        val parts = buildList {
            futures?.let { add("야간 코스피선물 ${signed(it.changeRate)}") }
            overseas.firstOrNull { it.label.contains("런던") }?.let { add("삼성(런던) ${signed(it.changeRate)}") }
        }
        val prefix = if (parts.isEmpty()) "" else parts.joinToString(" · ") + " → "
        return prefix + bias.label
    }

    /** +1.23% / -0.45% (소수 2자리, 부호 항상). */
    private fun signed(rate: Double): String {
        val s = if (rate >= 0) "+" else "-"
        return "%s%.2f%%".format(s, abs(rate))
    }
}
