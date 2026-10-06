package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/** Resolve the provider's actual exchange identifier; never guess NASDAQ/NYSE suffixes. */
@Component
class NaverGlobalQuoteClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver-global.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.naver-global.base-url:https://api.stock.naver.com}") private val baseUrl: String,
    @org.springframework.beans.factory.annotation.Qualifier("httpFetchExecutor") private val httpFetchExecutor: ExecutorService,
    private val searchClient: NaverStockSearchClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val cache = Caffeine.newBuilder().maximumSize(2_000)
        .expireAfterWrite(Duration.ofSeconds(45)).build<String, StockQuote>()

    fun fetchUsQuotes(tickers: Collection<String>): Map<String, StockQuote> {
        if (!enabled) return emptyMap()
        return tickers.map { it.uppercase() }.distinct()
            .filter { it.matches(Regex("[A-Z0-9][A-Z0-9.\\-]{0,14}")) }
            .map { ticker ->
                CompletableFuture.supplyAsync({
                    runCatching { cache.get(ticker) { fetchOne(it) } }
                        .onFailure { log.debug("US quote unavailable: ticker={}", ticker) }.getOrNull()
                }, httpFetchExecutor)
            }.mapNotNull { it.join() }.associateBy { it.ticker }
    }

    private fun fetchOne(ticker: String): StockQuote? {
        val symbol = searchClient.search(ticker, 50)
            .firstOrNull { it.market == "US" && it.ticker.equals(ticker, true) }
            ?.providerSymbol?.takeIf { it.matches(Regex("[A-Za-z0-9.\\-]{1,32}")) } ?: return null
        val request = HttpRequest.newBuilder().uri(URI.create("$baseUrl/stock/$symbol/basic"))
            .timeout(Duration.ofSeconds(4)).header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json").header("Referer", "https://m.stock.naver.com/")
            .GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        return parseQuote(objectMapper.readTree(response.body()), ticker, symbol)
    }

    internal fun parseQuote(root: JsonNode, ticker: String, symbol: String, now: Instant = Instant.now()): StockQuote? {
        if (!root.path("symbolCode").asText().equals(ticker, true) ||
            root.path("reutersCode").asText() != symbol ||
            root.path("currencyType").path("code").asText() != "USD" ||
            root.path("stockExchangeType").path("nationCode").asText() != "USA") return null
        fun number(name: String) = root.path(name).asText().replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }
        val close = number("closePrice")?.takeIf { it > 0 } ?: return null
        val rate = number("fluctuationsRatio") ?: return null
        val observed = runCatching { OffsetDateTime.parse(root.path("localTradedAt").asText()).toInstant() }.getOrNull() ?: return null
        if (observed > now.plusSeconds(60)) return null
        val delay = root.path("delayTime").takeIf { it.isIntegralNumber }?.asInt()?.takeIf { it >= 0 }
        return StockQuote(ticker, Math.round(close).toInt(), rate, close,
            quoteInfo = QuoteInfo(observedAt = observed.toString(), currency = "USD",
                session = root.path("marketStatus").asText("UNKNOWN"), delayMinutes = delay))
    }
}
