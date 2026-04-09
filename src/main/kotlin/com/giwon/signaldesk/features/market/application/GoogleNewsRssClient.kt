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
import javax.xml.parsers.DocumentBuilderFactory

@Component
class GoogleNewsRssClient(
    @Value("\${signal-desk.integrations.google-news.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.google-news.base-url:https://news.google.com/rss/search}") private val baseUrl: String,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchMarketNews(): List<MarketNews>? {
        if (!enabled) return null

        return runCatching {
            val krNews = fetchRss(
                market = "KR",
                query = "코스피 OR 코스닥 OR 한국 증시",
                impact = "한국 시장 주요 뉴스 흐름"
            )
            val usNews = fetchRss(
                market = "US",
                query = "NASDAQ OR S&P 500 OR US stock market",
                impact = "미국 시장 주요 뉴스 흐름"
            )
            (krNews + usNews).take(6)
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

        return (0 until minOf(nodes.length, 3)).mapNotNull { index ->
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
}
