package com.giwon.signaldesk.features.events.application

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
 * Finnhub earnings calendar.
 *   GET https://finnhub.io/api/v1/calendar/earnings?from=YYYY-MM-DD&to=YYYY-MM-DD&token=KEY[&symbol=NVDA]
 *
 * 응답:
 *   { "earningsCalendar": [ { "symbol":"NVDA","date":"2026-05-28","hour":"amc",
 *                             "epsEstimate":0.85,"quarter":2,"year":2026, ...} ] }
 *
 * 무료 티어 60 req/min. `FINNHUB_API_KEY` env 가 비어있으면 no-op (빈 리스트).
 */
@Component
class FinnhubClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.finnhub.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.finnhub.base-url:https://finnhub.io/api/v1}") private val baseUrl: String,
    @Value("\${signal-desk.integrations.finnhub.api-key:}") private val apiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @org.springframework.cache.annotation.Cacheable(
        cacheNames = ["macro-snapshot"],
        key = "'finnhub:earnings:' + #from + ':' + #to + ':' + (#symbol ?: 'ALL')",
        unless = "#result.isEmpty()",
    )
    fun fetchEarningsCalendar(from: String, to: String, symbol: String? = null): List<FinnhubEarning> {
        if (!enabled || apiKey.isBlank()) return emptyList()
        return runCatching {
            val symbolParam = symbol?.let { "&symbol=$it" } ?: ""
            val uri = URI.create("$baseUrl/calendar/earnings?from=$from&to=$to&token=$apiKey$symbolParam")
            val req = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json").GET().build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("Finnhub earnings non-2xx: status={}", resp.statusCode())
                return@runCatching emptyList()
            }
            val tree = objectMapper.readTree(resp.body())
            val arr = tree["earningsCalendar"] ?: return@runCatching emptyList()
            arr.mapNotNull { e ->
                val sym = e["symbol"]?.asText() ?: return@mapNotNull null
                val date = e["date"]?.asText() ?: return@mapNotNull null
                FinnhubEarning(
                    symbol = sym,
                    date = date,
                    hour = e["hour"]?.asText().orEmpty(),
                    epsEstimate = e["epsEstimate"]?.takeIf { !it.isNull }?.asDouble(),
                    quarter = e["quarter"]?.asInt() ?: 0,
                    year = e["year"]?.asInt() ?: 0,
                )
            }
        }.onFailure { log.warn("Finnhub earnings fetch failed: {}", it.message) }
            .getOrDefault(emptyList())
    }
}

data class FinnhubEarning(
    val symbol: String,
    val date: String,
    val hour: String,      // "amc" (장 마감 후), "bmo" (장 시작 전), "dmh" (장중)
    val epsEstimate: Double?,
    val quarter: Int,
    val year: Int,
)
