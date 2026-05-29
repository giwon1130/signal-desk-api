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
    private val globalQuoteClient: NaverGlobalQuoteClient,
) {

    /**
     * 종목 검색. 키워드 없으면 빈 결과.
     * KR·US 종목 모두 시세 API로 가격 보강 (US 는 검색 자동완성에 가격이 없어 별도 조회).
     */
    fun search(query: String, market: String?, limit: Int): List<StockSearchResult> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val raw = naverSearchClient.search(keyword, limit).filter { item ->
            val m = market?.uppercase()
            m.isNullOrBlank() || m == "ALL" || item.market.equals(m, true)
        }.take(limit)

        val krTickers = raw.filter { it.market == "KR" }.map { it.ticker }
        val usTickers = raw.filter { it.market == "US" }.map { it.ticker }
        val krQuotes = if (krTickers.isNotEmpty()) runCatching { naverClient.fetchKoreanQuotes(krTickers) }.getOrDefault(emptyMap()) else emptyMap()
        val usQuotes = if (usTickers.isNotEmpty()) runCatching { globalQuoteClient.fetchUsQuotes(usTickers) }.getOrDefault(emptyMap()) else emptyMap()

        return raw.map { item ->
            val quote = if (item.market == "KR") krQuotes[item.ticker] else usQuotes[item.ticker]
            quote?.let { item.copy(price = it.currentPrice, changeRate = it.changeRate) } ?: item
        }
    }
}
