package com.giwon.signaldesk.features.market.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Google News RSS 를 시장별 다중 쿼리로 병렬 수집.
 *
 * 기존에는 시장당 단일 쿼리 + 3건만 가져와서 sentiment 표본이 너무 작았다.
 * → 시장별 4~5개 쿼리를 병렬로 호출하고, 각 쿼리당 최대 15건을 받은 뒤
 *   URL 기준 중복 제거한 합집합을 돌려준다. 결과적으로 시장당 30~50건 수준의 표본이 확보된다.
 */
@Component
class GoogleNewsRssClient(
    @Value("\${signal-desk.integrations.google-news.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.google-news.base-url:https://news.google.com/rss/search}") private val baseUrl: String,
    @Value("\${signal-desk.integrations.google-news.per-query-limit:15}") private val perQueryLimit: Int,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchMarketNews(): List<MarketNews>? {
        if (!enabled) return null

        return runCatching {
            val krFutures = KR_QUERIES.map { query ->
                CompletableFuture.supplyAsync {
                    runCatching { fetchRss(market = "KR", query = query, impact = KR_IMPACT) }
                        .getOrElse { emptyList() }
                }
            }
            val usFutures = US_QUERIES.map { query ->
                CompletableFuture.supplyAsync {
                    runCatching { fetchRss(market = "US", query = query, impact = US_IMPACT) }
                        .getOrElse { emptyList() }
                }
            }
            val all = (krFutures + usFutures).flatMap { it.join() }
            // URL 기준 중복 제거 (같은 기사를 다른 쿼리가 집어올 수 있음)
            val deduped = all
                .asSequence()
                .filter { it.title.isNotBlank() && it.url.isNotBlank() }
                .distinctBy { it.url }
                .toList()
            deduped
        }.getOrNull()
    }

    private fun fetchRss(
        market: String,
        query: String,
        impact: String,
    ): List<MarketNews> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val uri = URI.create("$baseUrl?q=$encodedQuery&hl=ko&gl=KR&ceid=KR:ko")
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/rss+xml, application/xml;q=0.9, */*;q=0.8")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val builderFactory = DocumentBuilderFactory.newInstance()
        val builder = builderFactory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(response.body()))
        val nodes = document.getElementsByTagName("item")

        val limit = minOf(nodes.length, perQueryLimit)
        return (0 until limit).mapNotNull { index ->
            val item = nodes.item(index) as? Element ?: return@mapNotNull null
            val title = item.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()
            val link = item.getElementsByTagName("link").item(0)?.textContent?.trim().orEmpty()
            val source = item.getElementsByTagName("source").item(0)?.textContent?.trim()?.ifBlank { "Google News" } ?: "Google News"

            if (title.isBlank() || link.isBlank()) return@mapNotNull null

            MarketNews(
                market = market,
                title = title,
                source = source,
                url = link,
                impact = impact,
            )
        }
    }

    companion object {
        private const val KR_IMPACT = "한국 시장 주요 뉴스 흐름"
        private const val US_IMPACT = "미국 시장 주요 뉴스 흐름"

        // 다양한 각도에서 헤드라인을 수집하기 위해 쿼리를 다변화한다.
        // Google News 는 같은 검색어라도 시점/정렬에 따라 다른 결과를 주기 때문에 중복 제거가 필수.
        private val KR_QUERIES = listOf(
            "코스피",
            "코스닥",
            "한국 증시",
            "한국 주식 시장",
            "KOSPI OR KOSDAQ",
        )
        private val US_QUERIES = listOf(
            "S&P 500",
            "NASDAQ",
            "Dow Jones",
            "US stock market",
            "Wall Street",
        )
    }
}
