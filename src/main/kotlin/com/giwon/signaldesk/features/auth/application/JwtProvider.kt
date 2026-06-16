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
    @Value("\${signal-desk.store.mode:}") private val storeMode: String,
) {
    private val logger = LoggerFactory.getLogger(JwtProvider::class.java)
    private val key: SecretKey = Keys.hmacShaKeyFor(rawSecret.padEnd(32, 'x').toByteArray())

    @PostConstruct
    fun warnIfWeak() {
        // 운영(jdbc 모드)에서 더미 시크릿이면 토큰 위변조가 자유로움 → 조용한 보안 구멍보다 기동 실패가 낫다.
        // 로컬/file 모드는 경고만(개발 편의).
        if (rawSecret == DEFAULT_DUMMY) {
            if (storeMode.equals("jdbc", ignoreCase = true)) {
                throw IllegalStateException(
                    "[SECURITY] signal-desk.jwt.secret 미설정(기본 더미값). 운영에서는 JWT_SECRET(32자+ 랜덤)을 반드시 설정해야 기동됩니다.",
                )
            }
            logger.error("⚠️  [SECURITY] signal-desk.jwt.secret 가 기본 더미값 — 운영 전 32자 이상 랜덤 secret 으로 교체 필요.")
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
