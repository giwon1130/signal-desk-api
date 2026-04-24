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

@Component
class NaverFinanceQuoteClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.naver.base-url:https://polling.finance.naver.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchKoreanQuotes(tickers: Collection<String>): Map<String, StockQuote> {
        if (!enabled || tickers.isEmpty()) return emptyMap()

        return runCatching {
            val query = "SERVICE_ITEM:${tickers.distinct().joinToString(",")}"
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                .replace("%3A", ":")
                .replace("%2C", ",")
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/realtime?query=$encodedQuery"))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.naver.com/")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val root = objectMapper.readTree(response.body())
            val quotes = root["result"]
                ?.get("areas")
                ?.get(0)
                ?.get("datas")
                ?.elements()
                ?.asSequence()
                ?.associate { node ->
                    val ticker = node["cd"].asText()
                    // Naver 폴링 API 는 cr(등락률)을 '부호 없는 크기'로 준다.
                    // 방향은 rf 코드 ("2/3"=상승/보합, "4/5"=하한가/하락) 또는 nv<sv 로 판별.
                    // rf 가 신뢰가 안 될 때를 대비해 nv vs sv 비교를 1차 근거로 사용.
                    val nv = node["nv"]?.asInt() ?: 0
                    val sv = node["sv"]?.asInt() ?: 0
                    val rf = node["rf"]?.asText().orEmpty()
                    val magnitude = node["cr"]?.asDouble() ?: 0.0
                    val isDown = when {
                        sv > 0 && nv > 0 -> nv < sv
                        else -> rf == "4" || rf == "5"
                    }
                    val signedRate = if (isDown) -magnitude else magnitude
                    ticker to StockQuote(
                        ticker = ticker,
                        currentPrice = nv,
                        changeRate = signedRate,
                    )
                }
                .orEmpty()
            log.info(
                "Naver quote fetch completed. status={}, tickers={}, bodyPrefix={}, quotes={}",
                response.statusCode(),
                tickers.size,
                response.body().take(200),
                quotes
            )
            quotes
        }.getOrDefault(emptyMap())
    }
}

data class StockQuote(
    val ticker: String,
    val currentPrice: Int,
    val changeRate: Double,
)
