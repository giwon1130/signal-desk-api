package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 재시도 정책 회귀 보호:
 *  - 503/500/429(RPM 순간초과)는 재시도(true).
 *  - 429 라도 body 에 "quota"(일일 한도 소진)면 재시도하지 않음(false) → 즉시 다음 모델로 폴백.
 *    (쿼터 소진은 초 단위로 안 풀리는데 3모델×3시도로 9배 증폭되던 문제를 막는다.)
 *  - 400 등 hard error 는 재시도하지 않음.
 */
class GeminiClientTest {

    private val client = GeminiClient(
        objectMapper = ObjectMapper(),
        apiKey = "test-key",
        baseUrl = "http://localhost",
        model = "gemini-2.5-flash",
    )

    @Test
    fun `503 과부하는 재시도`() {
        assertThat(client.isRetryable(503, "service overloaded")).isTrue()
    }

    @Test
    fun `500 내부오류는 재시도`() {
        assertThat(client.isRetryable(500, null)).isTrue()
    }

    @Test
    fun `429 RPM 순간초과(quota 아님)는 재시도`() {
        assertThat(client.isRetryable(429, """{"error":{"message":"Rate limit exceeded, try again"}}""")).isTrue()
    }

    @Test
    fun `429 일일 쿼터 소진(body 에 quota)은 재시도하지 않음`() {
        val body = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details."}}"""
        assertThat(client.isRetryable(429, body)).isFalse()
    }

    @Test
    fun `quota 대소문자 무시`() {
        assertThat(client.isRetryable(429, "QUOTA exceeded")).isFalse()
    }

    @Test
    fun `400 hard error 는 재시도하지 않음`() {
        assertThat(client.isRetryable(400, "invalid argument")).isFalse()
    }
}
