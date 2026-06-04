package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * 재시도 정책 + 멀티키 폴백 회귀 보호.
 *
 * isRetryable:
 *  - 503/500/429(RPM 순간초과)는 재시도(true).
 *  - 429 라도 body 에 "quota"(일일 한도 소진)면 재시도하지 않음(false) → 즉시 다음 모델로.
 *  - 400 등 hard error 는 재시도하지 않음.
 *
 * 키 폴백(JDK 내장 HttpServer 로 Gemini v8 응답 스텁, key 쿼리파라미터로 분기):
 *  - primary 키가 모든 모델 429-quota → fallback 키로 넘어가 200 성공.
 *  - 모든 키가 quota 소진 → null.
 *  - fallback 비어있으면 단일 키 동작.
 */
class GeminiClientTest {

    private val objectMapper = ObjectMapper()

    private fun client(primary: String, fallbacks: String = "", baseUrl: String = "http://localhost") =
        GeminiClient(
            objectMapper = objectMapper,
            apiKey = primary,
            fallbackKeysRaw = fallbacks,
            baseUrl = baseUrl,
            model = "gemini-2.5-flash",
        )

    // ─── isRetryable 단위 테스트 ──────────────────────────────────────────────

    @Test
    fun `503 과부하는 재시도`() {
        assertThat(client("k").isRetryable(503, "service overloaded")).isTrue()
    }

    @Test
    fun `500 내부오류는 재시도`() {
        assertThat(client("k").isRetryable(500, null)).isTrue()
    }

    @Test
    fun `429 RPM 순간초과(quota 아님)는 재시도`() {
        assertThat(client("k").isRetryable(429, """{"error":{"message":"Rate limit exceeded, try again"}}""")).isTrue()
    }

    @Test
    fun `429 일일 쿼터 소진(body 에 quota)은 재시도하지 않음`() {
        val body = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details."}}"""
        assertThat(client("k").isRetryable(429, body)).isFalse()
    }

    @Test
    fun `quota 대소문자 무시`() {
        assertThat(client("k").isRetryable(429, "QUOTA exceeded")).isFalse()
    }

    @Test
    fun `400 hard error 는 재시도하지 않음`() {
        assertThat(client("k").isRetryable(400, "invalid argument")).isFalse()
    }

    // ─── 멀티키 폴백 통합 테스트 ───────────────────────────────────────────────

    private lateinit var server: HttpServer

    /** key 쿼리파라미터 → (statusCode, body). 없는 키는 403. */
    private val byKey = mutableMapOf<String, Pair<Int, String>>()

    private val quotaBody =
        """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details."}}"""

    private fun insightOk(headline: String): String {
        val inner = """{"headline":"$headline","summary":"s","sentiment":"NEUTRAL","keyPoints":["a"]}"""
        val root = objectMapper.createObjectNode()
        root.putArray("candidates").addObject()
            .putObject("content").putArray("parts").addObject().put("text", inner)
        return objectMapper.writeValueAsString(root)
    }

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query ?: ""
            val key = query.split("&").firstOrNull { it.startsWith("key=") }?.substringAfter("key=").orEmpty()
            val (code, body) = byKey[key] ?: (403 to "forbidden")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    @Test
    fun `primary 키 quota 소진 시 fallback 키로 폴백해 성공한다`() {
        byKey["primarykey1"] = 429 to quotaBody
        byKey["fallbackkey2"] = 200 to insightOk("폴백 성공")

        val result = client("primarykey1", fallbacks = "fallbackkey2", baseUrl = baseUrl())
            .summarizeMarketInsight(vix = null, indices = null, headlines = emptyList())

        assertThat(result).isNotNull
        assertThat(result!!.headline).isEqualTo("폴백 성공")
    }

    @Test
    fun `콤마로 여러 fallback 키 — 앞 키도 quota 면 다음 키로`() {
        byKey["k1"] = 429 to quotaBody
        byKey["k2"] = 429 to quotaBody
        byKey["k3"] = 200 to insightOk("세번째 키 성공")

        val result = client("k1", fallbacks = " k2 , k3 ", baseUrl = baseUrl())
            .summarizeMarketInsight(vix = null, indices = null, headlines = emptyList())

        assertThat(result?.headline).isEqualTo("세번째 키 성공")
    }

    @Test
    fun `모든 키가 quota 소진이면 null`() {
        byKey["k1"] = 429 to quotaBody
        byKey["k2"] = 429 to quotaBody

        val result = client("k1", fallbacks = "k2", baseUrl = baseUrl())
            .summarizeMarketInsight(vix = null, indices = null, headlines = emptyList())

        assertThat(result).isNull()
    }

    @Test
    fun `fallback 비어있으면 단일 키로 동작한다`() {
        byKey["solo"] = 200 to insightOk("단일 키")

        val result = client("solo", fallbacks = "", baseUrl = baseUrl())
            .summarizeMarketInsight(vix = null, indices = null, headlines = emptyList())

        assertThat(result?.headline).isEqualTo("단일 키")
    }
}
