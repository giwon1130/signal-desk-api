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
 * 시장별 상승률/하락률 상위 종목 리스트 ("급등 / 급락").
 *
 * 데이터 소스: Naver stock front-api 의 ranking 엔드포인트.
 *   GET https://m.stock.naver.com/api/stocks/{KOSPI|KOSDAQ}/{TYPE}?page=1&pageSize=10
 *   TYPE ∈ { up, down }  (상승률 / 하락률)
 *
 * 응답 예:
 *   {
 *     "stocks": [
 *       { "itemCode":"326030", "stockName":"SK바이오팜", "closePrice":"132,500",
 *         "compareToPreviousClosePrice":"30,500", "fluctuationsRatio":"29.90", ...},
 *       ...
 *     ],
 *     "totalCount": ...
 *   }
 *
 * 실패 시 빈 리스트를 반환하고 상위 레이어가 처리한다.
 */
@Component
class TopMoversClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver-top-movers.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.naver-top-movers.base-url:https://m.stock.naver.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchTopMovers(market: KoreanMarket, direction: Direction, limit: Int = 10): List<TopMover> {
        if (!enabled) return emptyList()

        val marketCode = when (market) {
            KoreanMarket.KOSPI -> "KOSPI"
            KoreanMarket.KOSDAQ -> "KOSDAQ"
        }
        val type = when (direction) {
            Direction.GAINERS -> "up"
            Direction.LOSERS -> "down"
        }

        val uri = URI.create("$baseUrl/api/stocks/$marketCode/$type?page=1&pageSize=$limit")
        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .header("Referer", "https://m.stock.naver.com/")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.warn("TopMovers fetch non-2xx. status={}, body={}", response.statusCode(), response.body().take(200))
                return@runCatching emptyList<TopMover>()
            }
            val root = objectMapper.readTree(response.body())
            val list = root["stocks"] ?: return@runCatching emptyList<TopMover>()
            list.take(limit).mapNotNull { node ->
                val ticker = node["itemCode"]?.asText().orEmpty()
                val name = node["stockName"]?.asText().orEmpty()
                if (ticker.isBlank() || name.isBlank()) return@mapNotNull null
                val price = node["closePrice"]?.asText()?.replace(",", "")?.toDoubleOrNull()?.toInt() ?: 0
                val rate = node["fluctuationsRatio"]?.asText()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                TopMover(
                    market = "KR",
                    ticker = ticker,
                    name = name,
                    price = price,
                    changeRate = rate,
                )
            }
        }.getOrElse {
            log.warn("TopMovers fetch exception. uri={}, err={}", uri, it.message)
            emptyList()
        }
    }

    enum class KoreanMarket { KOSPI, KOSDAQ }
    enum class Direction { GAINERS, LOSERS }
}

data class TopMover(
    val market: String,      // "KR" / "US"
    val ticker: String,
    val name: String,
    val price: Int,
    val changeRate: Double,
)

data class TopMoversResponse(
    val generatedAt: String,
    val kospi: TopMoversBlock,
    val kosdaq: TopMoversBlock,
)

data class TopMoversBlock(
    val gainers: List<TopMover>,
    val losers: List<TopMover>,
)
