package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * 핵심 회귀 보호:
 *  - fluctuationsRatio·compareToPreviousClosePrice 는 부호 없는 절대값 → compareToPreviousPrice 방향으로 부호를 붙인다.
 *  - closePrice 는 콤마 포함 문자열("1,470.40") → Double 파싱.
 *  - marketStatus 통과(야간 세션 활성 판별용), 404 는 null.
 */
class NaverIndexQuoteClientTest {

    private val objectMapper = ObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var client: NaverIndexQuoteClient
    private val responses = mutableMapOf<String, String>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/index/") { exchange ->
            // /api/index/{code}/basic → code 추출
            val code = exchange.requestURI.path.removePrefix("/api/index/").substringBefore("/")
            val body = responses[code]
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
        client = NaverIndexQuoteClient(
            objectMapper = objectMapper,
            enabled = true,
            baseUrl = "http://127.0.0.1:${server.address.port}",
        )
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun basicJson(price: String, changeAbs: String, ratio: Double, dirText: String, dirName: String, status: String): String = """
        {"stockName":"코스피 200 선물","closePrice":"$price",
         "compareToPreviousClosePrice":"$changeAbs",
         "compareToPreviousPrice":{"code":"2","text":"$dirText","name":"$dirName"},
         "fluctuationsRatio":$ratio,"marketStatus":"$status","localTradedAt":"2026-06-25T13:42:55+09:00"}
    """.trimIndent()

    @Test
    fun `상승이면 등락률·등락폭에 양수 부호, 콤마 가격 파싱`() {
        responses["FUT"] = basicJson("1,470.40", "88.55", ratio = 6.41, dirText = "상승", dirName = "RISING", status = "OPEN")

        val q = client.fetchQuote("FUT")

        assertThat(q).isNotNull
        assertThat(q!!.price).isCloseTo(1470.40, within(1e-6))
        assertThat(q.changeRate).isCloseTo(6.41, within(1e-6))
        assertThat(q.changeAmount).isCloseTo(88.55, within(1e-6))
        assertThat(q.marketStatus).isEqualTo("OPEN")
    }

    @Test
    fun `하락이면 절대값 등락률에 음수 부호를 붙인다`() {
        responses["FUT"] = basicJson("1,381.40", "12.30", ratio = 0.88, dirText = "하락", dirName = "FALLING", status = "CLOSE")

        val q = client.fetchQuote("FUT")

        assertThat(q).isNotNull
        assertThat(q!!.changeRate).isCloseTo(-0.88, within(1e-6))
        assertThat(q.changeAmount).isCloseTo(-12.30, within(1e-6))
        assertThat(q.marketStatus).isEqualTo("CLOSE")
    }

    @Test
    fun `보합이면 부호 0`() {
        responses["FUT"] = basicJson("1,400.00", "0.00", ratio = 0.0, dirText = "보합", dirName = "STEADY", status = "CLOSE")

        val q = client.fetchQuote("FUT")

        assertThat(q).isNotNull
        assertThat(q!!.changeRate).isEqualTo(0.0)
    }

    @Test
    fun `미등록 코드는 404 라 null`() {
        assertThat(client.fetchQuote("UNKNOWN")).isNull()
    }
}
