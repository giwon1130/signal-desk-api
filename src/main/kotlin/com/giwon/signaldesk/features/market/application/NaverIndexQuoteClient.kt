package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 국내 지수/선물의 라이브 시세(현재가·등락률·세션상태)를 Naver 모바일 API에서 가져온다.
 *
 *   GET https://m.stock.naver.com/api/index/{code}/basic
 *   code: KOSPI | KOSDAQ | FUT(코스피200 선물) ...
 *   응답: closePrice("1,470.40"), compareToPreviousClosePrice("88.55"),
 *         compareToPreviousPrice{text:"상승"/"하락"}, fluctuationsRatio(6.41, 부호없음),
 *         marketStatus("OPEN"/"CLOSE"), localTradedAt
 *
 * fluctuationsRatio 는 절대값이라 방향은 compareToPreviousPrice 로 부호를 붙인다.
 * 야간 코스피200 선물(FUT)의 야간 세션값이 여기 반영되며 marketStatus 로 세션 활성 여부를 안다.
 * 비공식 endpoint라 fail-soft(null) — 차트 OHLC 는 [NaverIndexChartClient] 가 담당, 이쪽은 라이브 1틱.
 */
@Component
class NaverIndexQuoteClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.naver-index.base-url:https://m.stock.naver.com}") private val baseUrl: String = "https://m.stock.naver.com",
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Cacheable(cacheNames = ["quote-short"], key = "'nidx:' + #code", unless = "#result == null")
    fun fetchQuote(code: String): IndexQuote? {
        if (!enabled) return null
        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/index/$code/basic"))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://m.stock.naver.com/")
                .header("Accept", "application/json")
                .GET().build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("Naver index quote {} returned status {}", code, res.statusCode())
                return null
            }
            val node = objectMapper.readTree(res.body())
            val price = parseNumber(node["closePrice"]?.asText()) ?: return null
            // fluctuationsRatio·compareToPreviousClosePrice 는 부호 없는 절대값 → 방향으로 부호를 붙인다.
            val sign = directionSign(node["compareToPreviousPrice"])
            val ratioAbs = node["fluctuationsRatio"]?.asDouble() ?: 0.0
            val changeAbs = parseNumber(node["compareToPreviousClosePrice"]?.asText()) ?: 0.0
            IndexQuote(
                code = code,
                name = node["stockName"]?.asText() ?: code,
                price = price,
                changeAmount = changeAbs * sign,
                changeRate = ratioAbs * sign,
                marketStatus = node["marketStatus"]?.asText() ?: "",
                tradedAt = node["localTradedAt"]?.asText() ?: "",
            )
        }.onFailure { log.warn("Naver index quote {} failed: {}", code, it.message) }.getOrNull()
    }

    /** compareToPreviousPrice {text:"상승"/"하락"/"보합", name:"RISING"/"FALLING"} → +1/-1/0. */
    private fun directionSign(node: com.fasterxml.jackson.databind.JsonNode?): Double {
        val text = node?.get("text")?.asText().orEmpty()
        val name = node?.get("name")?.asText().orEmpty().uppercase()
        return when {
            text.contains("하락") || name.contains("FALL") || name.contains("LOWER") -> -1.0
            text.contains("상승") || name.contains("RIS") || name.contains("UPPER") -> 1.0
            else -> 0.0
        }
    }

    /** "1,470.40" → 1470.40, null/빈값 → null. */
    private fun parseNumber(raw: String?): Double? =
        raw?.replace(",", "")?.trim()?.toDoubleOrNull()
}

data class IndexQuote(
    val code: String,
    val name: String,
    val price: Double,
    val changeAmount: Double,
    val changeRate: Double,    // 부호 포함 등락률 %
    val marketStatus: String,  // OPEN | CLOSE | ...
    val tradedAt: String,      // ISO local
)
