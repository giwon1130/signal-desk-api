package com.giwon.signaldesk.features.auth.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** JWT 발급/검증 — 라운드트립, 만료 거부, 서명 위조 거부. 인증 무결성의 핵심 경로. */
class JwtProviderTest {

    private val secret = "unit-test-secret-unit-test-secret-1234567890" // 32바이트+
    private fun provider(hours: Long = 720, sec: String = secret) =
        JwtProvider(rawSecret = sec, expirationHours = hours, storeMode = "file")

    @Test
    fun `발급한 토큰에서 userId 를 복원한다`() {
        val jwt = provider()
        val uid = UUID.randomUUID()
        val token = jwt.generate(uid, "user@example.com")
        assertThat(jwt.isValid(token)).isTrue()
        assertThat(jwt.extractUserId(token)).isEqualTo(uid)
        assertThat(jwt.parse(token).get("email", String::class.java)).isEqualTo("user@example.com")
    }

    @Test
    fun `만료된 토큰은 무효`() {
        val expired = provider(hours = -1) // exp 가 과거
        val token = expired.generate(UUID.randomUUID(), "user@example.com")
        assertThat(expired.isValid(token)).isFalse()
    }

    @Test
    fun `다른 비밀키로 서명한 토큰은 거부`() {
        val a = provider(sec = "AAAA-secret-AAAA-secret-AAAA-secret-1234567")
        val b = provider(sec = "BBBB-secret-BBBB-secret-BBBB-secret-1234567")
        val token = a.generate(UUID.randomUUID(), "user@example.com")
        assertThat(b.isValid(token)).isFalse()
    }

    @Test
    fun `변조된 토큰은 거부`() {
        val jwt = provider()
        val token = jwt.generate(UUID.randomUUID(), "user@example.com")
        val tampered = token.dropLast(3) + "xyz"
        assertThat(jwt.isValid(tampered)).isFalse()
    }
}
