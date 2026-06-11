package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import com.giwon.signaldesk.features.reading.domain.CallCurrency
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 콜 가격 lock / 현재가 조회 — League TradeService 의 fetchLockedPrice 와 동일 소스.
 * 리딩은 현금/환율 개념이 없어 시장 원통화 그대로 박제한다 (KR=KRW, US=USD).
 */
@Service
class ReadingPriceService(
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
) {
    data class LockedQuote(
        val price: BigDecimal,
        val currency: CallCurrency,
    )

    /** 작성 시점 시세 박제용. 실패 시 IllegalStateException (콜 등록 거부). 종목명은 작성자 확정값을 사용. */
    fun lock(market: String, ticker: String): LockedQuote = when (market) {
        "KR" -> {
            val q = krQuotes.fetchKoreanQuotes(listOf(ticker))[ticker]
                ?: error("price not available for KR:$ticker")
            require(q.exactPrice > 0) { "invalid price for KR:$ticker" }
            LockedQuote(BigDecimal.valueOf(q.exactPrice), CallCurrency.KRW)
        }
        "US" -> {
            val q = usQuotes.fetchUsQuotes(listOf(ticker))[ticker]
                ?: error("price not available for US:$ticker")
            require(q.exactPrice > 0) { "invalid price for US:$ticker" }
            LockedQuote(BigDecimal.valueOf(q.exactPrice), CallCurrency.USD)
        }
        else -> error("unknown market: $market")
    }

    /** 여러 종목 현재가 일괄 — 스케줄러/피드 성과계산용 (시장별 배치). */
    fun currentPrices(keys: Collection<Pair<String, String>>): Map<Pair<String, String>, BigDecimal> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<Pair<String, String>, BigDecimal>()
        val krTickers = keys.filter { it.first == "KR" }.map { it.second }.distinct()
        val usTickers = keys.filter { it.first == "US" }.map { it.second }.distinct()
        if (krTickers.isNotEmpty()) runCatching {
            krQuotes.fetchKoreanQuotes(krTickers).forEach { (t, q) ->
                if (q.exactPrice > 0) out["KR" to t] = BigDecimal.valueOf(q.exactPrice)
            }
        }
        if (usTickers.isNotEmpty()) runCatching {
            usQuotes.fetchUsQuotes(usTickers).forEach { (t, q) ->
                if (q.exactPrice > 0) out["US" to t] = BigDecimal.valueOf(q.exactPrice)
            }
        }
        return out
    }
}
