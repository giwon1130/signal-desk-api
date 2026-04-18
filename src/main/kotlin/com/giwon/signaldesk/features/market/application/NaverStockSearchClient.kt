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
 * 네이버 모바일 종목 검색 (KOSPI/KOSDAQ 전종목 + 미국주식 일부).
 *
 * 정적 레지스트리(StockSearchService)에서 못 찾은 키워드를 fallback 으로 처리한다.
 * 결과는 in-memory 캐시 (TTL 10분).
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

        val results = runCatching { fetchRemote(key, limit) }.getOrElse {
            log.warn("Naver stock search failed for '{}': {}", key, it.message)
            emptyList()
        }
        cache[key] = CacheEntry(results, now + ttlMs)
        return results
    }

    private fun fetchRemote(query: String, limit: Int): List<StockSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "https://m.stock.naver.com/api/search/total?query=$encoded&searchSection=stock&pageSize=$limit"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(4))
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://m.stock.naver.com/")
            .GET().build()

        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return emptyList()

        val root = mapper.readTree(res.body())
        // 응답 구조 (변경 가능): { "stocks": { "items": [ { "itemCode", "stockName", "nationCode", ... } ] } }
        val items = root.path("stocks").path("items")
        if (!items.isArray) return emptyList()

        return items.mapNotNull { node ->
            val ticker = node.path("itemCode").asText().ifBlank { null } ?: return@mapNotNull null
            val name   = node.path("stockName").asText().ifBlank { null } ?: return@mapNotNull null
            val nation = node.path("nationCode").asText().ifBlank { "KOR" }
            val market = if (nation.equals("USA", ignoreCase = true)) "US" else "KR"

            // 가격/등락 정보가 있으면 사용, 없으면 0
            val price = node.path("closePrice").asText("0")
                .replace(",", "").toDoubleOrNull()?.toInt() ?: 0
            val changeRate = node.path("fluctuationsRatio").asText("0")
                .replace(",", "").toDoubleOrNull() ?: 0.0
            val sector = node.path("industryName").asText("").ifBlank { "기타" }

            StockSearchResult(
                ticker     = ticker,
                name       = name,
                market     = market,
                sector     = sector,
                price      = price,
                changeRate = changeRate,
                stance     = "동적 검색 결과",
            )
        }
    }
}
