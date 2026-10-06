package com.giwon.signaldesk.features.events.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.*

data class EarningsCalendarResult(val status: String, val observedAt: String?, val entries: List<FinnhubEarning> = emptyList())

@Component
class FinnhubClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.finnhub.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.finnhub.base-url:https://finnhub.io/api/v1}") private val baseUrl: String,
    @Value("\${signal-desk.integrations.finnhub.api-key:}") private val apiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val cache = Caffeine.newBuilder().maximumSize(32).expireAfterWrite(Duration.ofMinutes(15))
        .build<String, EarningsCalendarResult>()
    @Volatile private var lastResult = EarningsCalendarResult("NOT_LOADED", null)

    fun status(): EarningsCalendarResult = if (!enabled || apiKey.isBlank()) EarningsCalendarResult("NOT_CONFIGURED", null) else lastResult.copy(entries = emptyList())

    fun fetchEarningsCalendar(from: String, to: String, symbol: String? = null): List<FinnhubEarning> =
        fetchCalendar(from, to, symbol).entries

    fun fetchCalendar(from: String, to: String, symbol: String? = null): EarningsCalendarResult {
        if (!enabled || apiKey.isBlank()) return EarningsCalendarResult("NOT_CONFIGURED", null)
        val valid = runCatching {
            val first = LocalDate.parse(from)
            val last = LocalDate.parse(to)
            !last.isBefore(first) && !last.isAfter(first.plusDays(90))
        }.getOrDefault(false)
        if (!valid || (symbol != null && !symbol.matches(Regex("[A-Z0-9][A-Z0-9.-]{0,14}")))) return EarningsCalendarResult("INVALID", null)
        return cache.get("$from:$to:${symbol.orEmpty()}") {
            val result = runCatching {
                val symbolParam = symbol?.let { "&symbol=$it" } ?: ""
                val key = URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8)
                val uri = URI.create("$baseUrl/calendar/earnings?from=$from&to=$to&token=$key$symbolParam")
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").GET().build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    log.warn("Finnhub earnings unavailable, status={}", response.statusCode())
                    return@runCatching EarningsCalendarResult("UNAVAILABLE", null)
                }
                parse(objectMapper.readTree(response.body()), LocalDate.parse(from), LocalDate.parse(to))
            }.getOrElse {
                // Exception messages can contain the request URL and token. Never log them.
                log.warn("Finnhub earnings request failed, type={}", it.javaClass.simpleName)
                EarningsCalendarResult("UNAVAILABLE", null)
            }
            lastResult = result
            result
        }
    }

    internal fun parse(root: JsonNode, from: LocalDate, to: LocalDate): EarningsCalendarResult {
        val entries = root["earningsCalendar"]?.takeIf { it.isArray } ?: return EarningsCalendarResult("UNAVAILABLE", null)
        val parsed = entries.mapNotNull { e ->
            val symbol = e["symbol"]?.asText()?.takeIf { it.matches(Regex("[A-Z0-9][A-Z0-9.-]{0,14}")) } ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(e["date"]?.asText()) }.getOrNull() ?: return@mapNotNull null
            if (date < from || date > to) return@mapNotNull null
            fun number(key: String) = e[key]?.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() }
            FinnhubEarning(symbol, date.toString(), e["hour"]?.asText().orEmpty(), number("epsEstimate"),
                e["quarter"]?.asInt()?.takeIf { it in 1..4 } ?: 0, e["year"]?.asInt() ?: 0,
                number("epsActual"), number("revenueEstimate"), number("revenueActual"))
        }.distinctBy { "${it.symbol}:${it.date}:${it.quarter}:${it.year}" }
        return EarningsCalendarResult("AVAILABLE", Instant.now().toString(), parsed)
    }
}

data class FinnhubEarning(
    val symbol: String, val date: String, val hour: String, val epsEstimate: Double?,
    val quarter: Int, val year: Int, val epsActual: Double? = null,
    val revenueEstimate: Double? = null, val revenueActual: Double? = null,
)
