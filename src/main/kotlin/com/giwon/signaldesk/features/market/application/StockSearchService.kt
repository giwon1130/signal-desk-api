package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

data class StockSearchResult(
    val ticker: String,
    val name: String,
    val market: String,
    val sector: String,
    val price: Int,
    val changeRate: Double,
    val stance: String,
)

@Service
class StockSearchService(
    private val naverClient: NaverFinanceQuoteClient,
    private val naverSearchClient: NaverStockSearchClient,
) {

    /**
     * 종목 검색. 키워드 없으면 빈 결과.
     * KR 종목은 Naver 시세 API로 가격 보강.
     */
    fun search(query: String, market: String?, limit: Int): List<StockSearchResult> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val raw = naverSearchClient.search(keyword, limit).filter { item ->
            val m = market?.uppercase()
            m.isNullOrBlank() || m == "ALL" || item.market.equals(m, true)
        }.take(limit)

        val krTickers = raw.filter { it.market == "KR" }.map { it.ticker }
        val quotes = if (krTickers.isNotEmpty()) naverClient.fetchKoreanQuotes(krTickers) else emptyMap()

        return raw.map { item ->
            if (item.market == "KR") {
                quotes[item.ticker]?.let { quote ->
                    item.copy(price = quote.currentPrice, changeRate = quote.changeRate)
                } ?: item
            } else {
                item
            }
        }
    }
}
