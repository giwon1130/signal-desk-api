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
 * 한국 지수의 일/주/월봉 OHLC 데이터를 Naver Stock API에서 가져온다.
 *
 * 엔드포인트: https://api.stock.naver.com/chart/domestic/index/{KOSPI|KOSDAQ}
 *   ?periodType={dayCandle|weekCandle|monthCandle}&count={N}
 *
 * 응답 예:
 *   {"priceInfos":[{"localDate":"20251112","openPrice":4097.44,"highPrice":4154.62,
 *                   "lowPrice":4088.86,"closePrice":4150.39,"accumulatedTradingVolume":334584,...}]}
 *
 * 실패하면 빈 리스트 반환 → IndexChartFactory에서 시뮬레이션으로 폴백.
 */
@Component
class NaverIndexChartClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    enum class PeriodType(val raw: String) {
        DAILY("dayCandle"),
        WEEKLY("weekCandle"),
        MONTHLY("monthCandle"),
    }

    /**
     * @param indexCode "KOSPI" or "KOSDAQ"
     * @param periodType 캔들 주기
     * @param count 가져올 캔들 개수 (Naver는 최소 110개를 항상 반환하므로 truncate는 호출자가)
     */
    fun fetchOhlc(indexCode: String, periodType: PeriodType, count: Int = 30): List<IndexCandle> {
        if (!enabled) return emptyList()

        return runCatching {
            val url = "https://api.stock.naver.com/chart/domestic/index/$indexCode" +
                "?periodType=${periodType.raw}&count=$count"
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build()

            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("Naver chart {} {} returned status {}", indexCode, periodType.raw, res.statusCode())
                return emptyList()
            }

            val node = objectMapper.readTree(res.body())
            val priceInfos = node["priceInfos"] ?: return emptyList()

            priceInfos.mapNotNull { item ->
                runCatching {
                    IndexCandle(
                        date = item["localDate"].asText(),
                        open = item["openPrice"].asDouble(),
                        high = item["highPrice"].asDouble(),
                        low = item["lowPrice"].asDouble(),
                        close = item["closePrice"].asDouble(),
                        volume = item["accumulatedTradingVolume"]?.asLong() ?: 0L,
                    )
                }.getOrNull()
            }
        }.onFailure { log.warn("Naver chart {} {} failed: {}", indexCode, periodType.raw, it.message) }
            .getOrDefault(emptyList())
    }
}

data class IndexCandle(
    val date: String,   // YYYYMMDD
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
