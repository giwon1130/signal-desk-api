package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 핵심 회귀 보호:
 *  - 일간 등락률은 close 배열의 '직전 종가'로 계산하고 chartPreviousClose(=range 시작 전 종가)는 쓰지 않는다.
 *    (이걸 어기면 닛케이/항셍/선물 등락률이 수일치로 과대표기 + 미국 지수 방향 오인으로 이어진다.)
 *  - 한 심볼이라도 실패하면 fetchUsIndices 는 null → 상위(UsIndexService)가 FRED 로 폴백.
 *  - priorClose 의 마감/장중/데이터부족 분기.
 *
 * 외부 의존 없이 JDK 내장 HttpServer 로 야후 v8 chart 응답을 스텁한다.
 */
class YahooQuoteClientTest {

    private val objectMapper = ObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var client: YahooQuoteClient

    /** 디코드된 심볼(예: "^GSPC") → 응답 JSON. 없으면 404. */
    private val responses = mutableMapOf<String, String>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v8/finance/chart/") { exchange ->
            val symbol = URLDecoder.decode(exchange.requestURI.rawPath, "UTF-8").substringAfterLast("/")
            val body = responses[symbol]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        client = YahooQuoteClient(
            objectMapper = objectMapper,
            enabled = true,
            baseUrl = "http://127.0.0.1:${server.address.port}",
        )
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    /** 지수처럼 previousClose=null, chartPreviousClose 는 일부러 멀리 둬서 '쓰면 틀리게' 만든다. */
    private fun chartJson(price: Double, closes: List<Double?>, chartPreviousClose: Double): String {
        val closeArr = closes.joinToString(",") { it?.toString() ?: "null" }
        return """
            {"chart":{"result":[{
              "meta":{"regularMarketPrice":$price,"previousClose":null,"chartPreviousClose":$chartPreviousClose},
              "indicators":{"quote":[{"close":[$closeArr]}]}
            }]}}
        """.trimIndent()
    }

    @Test
    fun `fetchUsIndices - 등락률은 close 배열 직전 종가 기준이고 chartPreviousClose 는 무시한다`() {
        // ^GSPC: 어젯밤 하락 7609.78 -> 7553.68 = -0.737%. chartPreviousClose(7000)로 계산하면 +7.9% 라 둘이 분명히 갈린다.
        // 중간 null 은 필터링돼야 한다.
        responses["^GSPC"] = chartJson(7553.68, listOf(7500.0, null, 7609.78, 7553.68), chartPreviousClose = 7000.0)
        responses["^IXIC"] = chartJson(23000.0, listOf(23250.0, 23100.0), chartPreviousClose = 20000.0)

        val snap = client.fetchUsIndices()

        assertThat(snap).isNotNull
        assertThat(snap!!.sp500.currentValue).isEqualTo(7553.68)
        assertThat(snap.sp500.changeRate).isCloseTo(-0.737, within(0.01)) // chartPreviousClose 였다면 +7.9%
        assertThat(snap.nasdaq.currentValue).isEqualTo(23000.0)
        assertThat(snap.nasdaq.changeRate).isCloseTo(-0.433, within(0.01))
    }

    @Test
    fun `fetchUsIndices - 한 심볼이라도 실패하면 null 로 상위 FRED 폴백에 넘긴다`() {
        responses["^GSPC"] = chartJson(7553.68, listOf(7609.78, 7553.68), chartPreviousClose = 7000.0)
        // ^IXIC 미등록 → 404 → null → 전체 null
        assertThat(client.fetchUsIndices()).isNull()
    }

    @Test
    fun `fetchIndices(글로벌) - 닛케이 등락률도 chartPreviousClose 가 아닌 직전 종가 기준`() {
        // 마지막 close == price(67500) → 직전 종가 66740 기준 = +1.138%. chartPreviousClose(66329.5)면 +1.77%.
        responses["^N225"] = chartJson(67500.0, listOf(66800.0, 66740.0, 67500.0), chartPreviousClose = 66329.5)

        val result = client.fetchIndices(linkedMapOf("^N225" to "닛케이225"))

        assertThat(result).hasSize(1)
        assertThat(result[0].label).isEqualTo("닛케이225")
        assertThat(result[0].value).isEqualTo(67500.0)
        assertThat(result[0].changeRate).isCloseTo(1.138, within(0.01))
    }

    @Test
    fun `priorClose - 마감 후(마지막 close==현재가)면 그 직전 종가를 쓴다`() {
        assertThat(client.priorClose(listOf(100.0, 110.0, 121.0), price = 121.0)).isEqualTo(110.0)
    }

    @Test
    fun `priorClose - 장중(마지막 close != 현재가)이면 마지막 close 를 쓴다`() {
        assertThat(client.priorClose(listOf(100.0, 110.0), price = 115.5)).isEqualTo(110.0)
    }

    @Test
    fun `priorClose - 데이터가 부족하면 null`() {
        assertThat(client.priorClose(emptyList(), price = 100.0)).isNull()
        assertThat(client.priorClose(listOf(100.0), price = 100.0)).isNull() // 마지막==price 인데 직전이 없음
    }
}
