package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Provider OHLC only. Never construct candles from a close-only series or the latest quote. */
@Component
class YahooCandleClient(
    private val mapper: ObjectMapper,
    @Value("\${signal-desk.integrations.yahoo-quote.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.yahoo-quote.base-url:https://query1.finance.yahoo.com}") private val baseUrl: String,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    // Cache failures too: an unavailable provider must not be retried for every app refresh.
    @Cacheable(cacheNames = ["chart-mid"], key = "'yahoo-ohlc:' + #symbol + ':' + #range", sync = true)
    fun fetch(symbol: String, range: String = "5y"): List<IndexCandle> {
        if (!enabled || !symbol.matches(Regex("[A-Z0-9^][A-Z0-9.^-]{0,19}")) || range !in setOf("6mo", "5y")) return emptyList()
        return runCatching {
            val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/v8/finance/chart/$encoded?range=$range&interval=1d"))
                .timeout(Duration.ofSeconds(5)).header("User-Agent", "Mozilla/5.0").GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching emptyList()
            val result = mapper.readTree(response.body())["chart"]?.get("result")?.get(0) ?: return@runCatching emptyList()
            parse(result, symbol)
        }.getOrDefault(emptyList())
    }

    internal fun parse(result: JsonNode, symbol: String, now: Instant = Instant.now()): List<IndexCandle> {
        val meta = result["meta"] ?: return emptyList()
        if (meta["symbol"]?.asText() != symbol) return emptyList()
        if (meta["currency"]?.asText() != "USD") return emptyList()
        val zone = runCatching { ZoneId.of(meta["exchangeTimezoneName"]?.asText()) }.getOrNull() ?: return emptyList()
        if (zone.id != "America/New_York") return emptyList()
        val today = now.atZone(zone).toLocalDate()
        val quote = result["indicators"]?.get("quote")?.get(0) ?: return emptyList()
        return result["timestamp"]?.mapIndexedNotNull { i, timestamp ->
            if (!timestamp.isIntegralNumber || timestamp.asLong() <= 0 || timestamp.asLong() > now.epochSecond) return@mapIndexedNotNull null
            val date = Instant.ofEpochSecond(timestamp.asLong()).atZone(zone).toLocalDate()
            fun price(key: String) = quote[key]?.get(i)?.takeIf { it.isNumber }?.asDouble()?.takeIf { it.isFinite() && it > 0 }
            val open = price("open") ?: return@mapIndexedNotNull null
            val high = price("high") ?: return@mapIndexedNotNull null
            val low = price("low") ?: return@mapIndexedNotNull null
            val close = price("close") ?: return@mapIndexedNotNull null
            val volume = quote["volume"]?.get(i)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
                ?.takeIf { it >= 0 } ?: return@mapIndexedNotNull null
            if (high < maxOf(open, close) || low > minOf(open, close)) return@mapIndexedNotNull null
            IndexCandle(date.format(DateTimeFormatter.BASIC_ISO_DATE), open, high, low, close, volume,
                provisional = date == today)
        }.orEmpty().groupBy { it.date }.filterValues { it.size == 1 }.values.map { it.single() }.sortedBy { it.date }
    }
}

/** Aggregate actual daily bars; the current week/month remains explicitly provisional. */
internal fun aggregateCandles(daily: List<IndexCandle>, monthly: Boolean, today: LocalDate): List<IndexCandle> {
    fun bucket(date: LocalDate) = if (monthly) date.withDayOfMonth(1) else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return daily.groupBy { bucket(LocalDate.parse(it.date, DateTimeFormatter.BASIC_ISO_DATE)) }.toSortedMap().mapNotNull { (key, rows) ->
        val sorted = rows.sortedBy { it.date }
        val volume = runCatching { sorted.fold(0L) { sum, c -> Math.addExact(sum, c.volume) } }.getOrNull() ?: return@mapNotNull null
        IndexCandle(sorted.last().date, sorted.first().open, sorted.maxOf { it.high }, sorted.minOf { it.low }, sorted.last().close,
            volume, provisional = key == bucket(today) || sorted.any { it.provisional })
    }
}

fun usChartPeriods(candles: List<IndexCandle>, today: LocalDate = LocalDate.now(ZoneId.of("America/New_York"))): List<ChartPeriodSnapshot> =
    buildIndexChartPeriodsFromOhlc(0.0, 0.0, candles, aggregateCandles(candles, false, today), aggregateCandles(candles, true, today))
        .map { it.copy(source = "Yahoo Finance", priceBasis = "PROVIDER_OHLC", asOf = candles.lastOrNull()?.date,
            note = "미 동부 거래일 기준 제공 OHLC입니다. 당일·이번 주·이번 달 봉은 미확정이며, 배당 포함 수익률 차트가 아닙니다.") }
