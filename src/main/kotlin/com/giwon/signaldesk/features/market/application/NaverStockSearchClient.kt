package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 네이버 종목 자동완성 검색.
 *
 * 엔드포인트: https://ac.stock.naver.com/ac?q={query}&target=stock
 *
 * 응답 예:
 *  {
 *    "query": "삼성",
 *    "items": [
 *      {"code":"005930","name":"삼성전자","typeCode":"KOSPI","typeName":"코스피",
 *       "reutersCode":"005930","nationCode":"KOR","nationName":"대한민국","category":"stock"},
 *      {"code":"AAPL","name":"애플","typeCode":"NASDAQ","typeName":"나스닥 증권거래소",
 *       "reutersCode":"AAPL.O","nationCode":"USA","nationName":"미국","category":"stock"}
 *    ]
 *  }
 *
 * KOR/USA 종목만 필터링한다(JPN/CHN/EUR 제외). 정적 레지스트리(StockSearchService)에서 못 찾은
 * 키워드를 fallback 으로 처리하며, 결과는 in-memory 캐시 (TTL 10분).
 */
@Component
class NaverStockSearchClient(private val mapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    private data class CacheEntry(val results: List<StockSearchResult>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val ttlMs = 10 * 60 * 1000L

    fun search(query: String, limit: Int = 20): List<StockSearchResult> {
        val key = query.trim().lowercase()
        if (key.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expiresAt > now) return it.results.take(limit) }

        val results = runCatching { fetchRemote(key) }.getOrElse {
            log.warn("Naver stock search failed for '{}': {}", key, it.message)
            emptyList()
        }
        cache[key] = CacheEntry(results, now + ttlMs)
        return results.take(limit)
    }

    private fun fetchRemote(query: String): List<StockSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "https://ac.stock.naver.com/ac?q=$encoded&target=stock"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(4))
            .header("User-Agent", "Mozilla/5.0")
            .GET().build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return emptyList()

        val root = mapper.readTree(res.body())
        val items = root.path("items")
        if (!items.isArray) return emptyList()

        return items.mapNotNull { node ->
            val ticker = node.path("code").asText().ifBlank { null } ?: return@mapNotNull null
            val name   = node.path("name").asText().ifBlank { null } ?: return@mapNotNull null
            val nation = node.path("nationCode").asText().ifBlank { "KOR" }
            val market = when {
                nation.equals("KOR", ignoreCase = true) -> "KR"
                nation.equals("USA", ignoreCase = true) -> "US"
                else -> return@mapNotNull null   // JPN/CHN/EUR 등은 일단 제외
            }
            // 자동완성 응답에는 typeCode(KOSPI/NASDAQ 등)만 있고 섹터는 없다.
            val sector = node.path("typeCode").asText("기타")

            StockSearchResult(
                ticker     = ticker,
                name       = name,
                market     = market,
                sector     = sector,
                price      = 0,            // 가격은 StockSearchService에서 enrich
                changeRate = 0.0,
                stance     = "동적 검색 결과",
            )
        }
    }
}
