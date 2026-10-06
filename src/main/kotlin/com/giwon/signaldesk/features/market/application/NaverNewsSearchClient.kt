package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Official search API. No article-body scraping; credentials never enter URLs/logs. */
@Component
class NaverNewsSearchClient(
    private val mapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver-news.enabled:false}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.naver-news.client-id:}") private val clientId: String,
    @Value("\${signal-desk.integrations.naver-news.client-secret:}") private val clientSecret: String,
    @Value("\${signal-desk.integrations.naver-news.base-url:https://openapi.naver.com/v1/search/news.json}") private val baseUrl: String,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private var day = LocalDate.now(ZoneOffset.UTC)
    private var calls = 0
    @Synchronized private fun reserve(): Boolean {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (today != day) { day = today; calls = 0 }
        if (calls >= 1000) return false // Conservative per-process budget; cache lives in MoverReasonService.
        calls++
        return true
    }

    fun search(target: MoverReasonTarget): NewsSearchResult {
        if (!enabled || clientId.isBlank() || clientSecret.isBlank() || target.market != "KR") return NewsSearchResult("DISABLED")
        if (!reserve()) return NewsSearchResult("UNAVAILABLE")
        return runCatching {
            val query = URLEncoder.encode(target.name, StandardCharsets.UTF_8)
            val req = HttpRequest.newBuilder(URI.create("$baseUrl?query=$query&display=30&sort=date"))
                .timeout(Duration.ofSeconds(5)).header("X-Naver-Client-Id", clientId)
                .header("X-Naver-Client-Secret", clientSecret).GET().build()
            val response = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299 || response.body().length > 1_000_000) return NewsSearchResult("UNAVAILABLE")
            decode(response.body())
        }.getOrElse { NewsSearchResult("UNAVAILABLE") }
    }

    internal fun decode(body: String): NewsSearchResult {
        val root = mapper.readTree(body)
        val items = root.path("items")
        if (!items.isArray) return NewsSearchResult("UNAVAILABLE")
        return NewsSearchResult("SUCCESS", items.take(30).mapNotNull { item ->
            val title = HtmlUtils.htmlUnescape(item.path("title").asText().replace(Regex("</?b>"), "")).trim()
            val url = item.path("originallink").asText().ifBlank { item.path("link").asText() }
            if (title.isBlank() || !StockMoveContextBuilder.safeUrl(url)) return@mapNotNull null
            val timestamp = runCatching { ZonedDateTime.parse(item.path("pubDate").asText(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull()
            // The API's pubDate may be portal-provided time, not the original event time.
            MarketNews("KR", title, URI(url).host, url, "네이버 뉴스 검색 · 원문 제공 시각", timestamp)
        })
    }
}
