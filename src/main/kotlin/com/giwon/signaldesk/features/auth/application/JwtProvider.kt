package com.giwon.signaldesk.features.auth.application

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtProvider(
    @Value("\${signal-desk.jwt.secret:change-me-please-make-this-at-least-32-bytes-long-secret}") private val rawSecret: String,
    @Value("\${signal-desk.jwt.expiration-hours:720}") private val expirationHours: Long,
) {
    private val logger = LoggerFactory.getLogger(JwtProvider::class.java)
    private val key: SecretKey = Keys.hmacShaKeyFor(rawSecret.padEnd(32, 'x').toByteArray())

    @PostConstruct
    fun warnIfWeak() {
        // 운영에서 기본 더미값 그대로 쓰면 토큰 위변조가 사실상 자유로움 → 부팅 시 시끄럽게 경고.
        // 자동 fail-fast 는 안 함 (Railway 가 즉시 unhealthy 로 떨궈서 트래픽 끊김 → 운영자가 secret 못 넣고 디버깅 불가).
        if (rawSecret == DEFAULT_DUMMY) {
            logger.error("⚠️  [SECURITY] signal-desk.jwt.secret 가 기본 더미값. 운영에서는 32자 이상 랜덤 secret 으로 반드시 교체.")
        } else if (rawSecret.length < 32) {
            logger.warn("⚠️  [SECURITY] signal-desk.jwt.secret 길이 ${rawSecret.length}자 — 32자 이상 권장.")
        }
    }

    fun generate(userId: UUID, email: String): String {
        val now = Date()
        val exp = Date(now.time + expirationHours * 3_600_000L)
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(now)
            .expiration(exp)
            .signWith(key)
            .compact()
    }

    fun parse(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload

    fun extractUserId(token: String): UUID =
        UUID.fromString(parse(token).subject)

    fun isValid(token: String): Boolean = runCatching { parse(token) }.isSuccess

    companion object {
        private const val DEFAULT_DUMMY = "change-me-please-make-this-at-least-32-bytes-long-secret"
    }
}
