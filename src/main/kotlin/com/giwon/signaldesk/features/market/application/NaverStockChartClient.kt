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

data class DailyBar(val date: String, val close: Int, val volume: Long)

/**
 * 개별 KR 종목의 일봉 OHLCV를 Naver Stock API에서 가져온다.
 *
 * 엔드포인트: https://api.stock.naver.com/chart/domestic/item/{ticker}
 *   ?periodType=dayCandle&count={N}
 *
 * 응답 예:
 *   {"priceInfos":[{"localDate":"20260513","openPrice":84200,"highPrice":84400,
 *                   "lowPrice":83800,"closePrice":84100,"accumulatedTradingVolume":500000,...}]}
 *
 * 실패하면 빈 리스트 반환 → TechnicalIndicatorCalculator에서 null TechnicalSignal 반환.
 */
@Component
class NaverStockChartClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun fetchDailyBars(ticker: String, count: Int = 30): List<DailyBar> {
        if (!enabled) return emptyList()
        return runCatching {
            val url = "https://api.stock.naver.com/chart/domestic/item/$ticker?periodType=dayCandle&count=$count"
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("NaverStockChartClient {} returned {}", ticker, res.statusCode())
                return emptyList()
            }
            val node = objectMapper.readTree(res.body())
            val priceInfos = node["priceInfos"] ?: return emptyList()
            priceInfos.mapNotNull { item ->
                runCatching {
                    DailyBar(
                        date = item["localDate"].asText(),
                        close = item["closePrice"].asDouble().toInt(),
                        volume = item["accumulatedTradingVolume"]?.asLong() ?: 0L,
                    )
                }.getOrNull()
            }
        }.onFailure { log.warn("NaverStockChartClient failed ticker={} reason={}", ticker, it.message) }
            .getOrDefault(emptyList())
    }
}
