package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Yahoo Finance v8 chart API 로 임의 심볼(글로벌 지수·선물)의 현재가/등락률을 가져온다.
 *
 *   GET https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=5d&interval=1d
 *   응답: chart.result[0].meta.{regularMarketPrice, previousClose|chartPreviousClose}
 *
 * 닛케이(^N225)·항셍(^HSI)·S&P 선물(ES=F) 등 야간/아시아 방향성 지표용.
 * 비공식 endpoint라 fail-soft (빈 결과).
 */
@Component
class YahooQuoteClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.yahoo-quote.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.yahoo-quote.base-url:https://query1.finance.yahoo.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    /** symbolToLabel: Yahoo 심볼 → 표시 라벨. 병렬 조회, 성공한 항목만 입력 순서대로 반환. */
    @org.springframework.cache.annotation.Cacheable(
        cacheNames = ["macro-index"],
        key = "'yq:' + new java.util.TreeSet(#symbolToLabel.keySet()).toString()",
        unless = "#result.isEmpty()",
    )
    fun fetchIndices(symbolToLabel: Map<String, String>): List<GlobalIndex> {
        if (!enabled || symbolToLabel.isEmpty()) return emptyList()
        val futures = symbolToLabel.entries.map { (symbol, label) ->
            CompletableFuture.supplyAsync {
                runCatching { fetchOne(symbol, label) }
                    .onFailure { log.debug("Yahoo quote failed. symbol={}, err={}", symbol, it.message) }
                    .getOrNull()
            }
        }
        return futures.mapNotNull { it.join() }
    }

    private fun fetchOne(symbol: String, label: String): GlobalIndex? {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v8/finance/chart/$encoded?range=5d&interval=1d"))
            .timeout(Duration.ofSeconds(5))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .GET().build()
        val resp = runCatching { httpClient.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (resp.statusCode() !in 200..299) return null
        val meta = runCatching { objectMapper.readTree(resp.body()) }.getOrNull()
            ?.get("chart")?.get("result")?.get(0)?.get("meta") ?: return null
        val price = meta["regularMarketPrice"]?.asDouble() ?: return null
        val prevClose = (meta["previousClose"] ?: meta["chartPreviousClose"])?.asDouble()
        val changeRate = if (prevClose != null && prevClose != 0.0) (price - prevClose) / prevClose * 100 else 0.0
        return GlobalIndex(label = label, value = price, changeRate = changeRate)
    }

    companion object {
        /** 브리프 야간 방향성용 — 닛케이·항셍·S&P500 선물. */
        val GLOBAL_INDICES = linkedMapOf(
            "^N225" to "닛케이225",
            "^HSI" to "항셍",
            "ES=F" to "S&P500 선물",
        )
    }
}

data class GlobalIndex(
    val label: String,
    val value: Double,
    val changeRate: Double,
)
