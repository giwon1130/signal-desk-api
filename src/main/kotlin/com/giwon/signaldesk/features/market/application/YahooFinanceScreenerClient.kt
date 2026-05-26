package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Yahoo Finance 사전 정의 screener (`day_gainers` / `day_losers` / `most_actives`)를 통해
 * 미국 시장 일간 급등·급락·거래량 상위 종목을 가져온다.
 *
 * 엔드포인트:
 *   GET https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved
 *       ?scrIds={day_gainers|day_losers|most_actives}&count=N
 *
 * 응답 핵심 필드(`finance.result[0].quotes[*]`):
 *   - symbol, shortName/longName, regularMarketPrice, regularMarketChangePercent,
 *     regularMarketVolume, averageDailyVolume3Month, fullExchangeName
 *
 * 비공식 endpoint라 언제든 깨질 수 있어 fail-soft (빈 리스트 반환).
 * KR Naver scraping 패턴과 동일한 운영 정책.
 */
@Component
class YahooFinanceScreenerClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.yahoo-screener.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.yahoo-screener.base-url:https://query1.finance.yahoo.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @org.springframework.cache.annotation.Cacheable(
        cacheNames = ["top-movers"],
        key = "'us:' + #screen + ':' + #limit",
        unless = "#result.isEmpty()",
    )
    fun fetchScreener(screen: String, limit: Int = 10): List<YahooQuote> {
        if (!enabled) return emptyList()
        return runCatching {
            val uri = URI.create("$baseUrl/v1/finance/screener/predefined/saved?scrIds=$screen&count=$limit")
            val req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .GET().build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("Yahoo screener non-2xx. screen={}, status={}", screen, resp.statusCode())
                return@runCatching emptyList()
            }
            val tree = objectMapper.readTree(resp.body())
            val quotes = tree["finance"]?.get("result")?.get(0)?.get("quotes")
                ?: return@runCatching emptyList()
            quotes.mapNotNull { q ->
                val symbol = q["symbol"]?.asText() ?: return@mapNotNull null
                YahooQuote(
                    ticker = symbol,
                    name = q["shortName"]?.asText() ?: q["longName"]?.asText() ?: symbol,
                    price = q["regularMarketPrice"]?.asDouble() ?: 0.0,
                    changeRate = q["regularMarketChangePercent"]?.asDouble() ?: 0.0,
                    volume = q["regularMarketVolume"]?.asLong() ?: 0L,
                    avgVolume = q["averageDailyVolume3Month"]?.asLong(),
                    exchange = q["fullExchangeName"]?.asText() ?: q["exchange"]?.asText() ?: "",
                )
            }
        }.onFailure { log.warn("Yahoo screener fetch failed. screen={}, err={}", screen, it.message) }
            .getOrDefault(emptyList())
    }

    fun fetchGainers(limit: Int = 10): List<YahooQuote> = fetchScreener("day_gainers", limit)
    fun fetchLosers(limit: Int = 10): List<YahooQuote> = fetchScreener("day_losers", limit)
    fun fetchMostActives(limit: Int = 10): List<YahooQuote> = fetchScreener("most_actives", limit)
}

data class YahooQuote(
    val ticker: String,
    val name: String,
    val price: Double,
    val changeRate: Double,
    val volume: Long,
    val avgVolume: Long?,
    val exchange: String,
)
