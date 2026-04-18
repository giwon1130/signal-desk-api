package com.giwon.signaldesk.features.auth.application

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Component
class JwtProvider(
    @Value("\${signal-desk.jwt.secret:change-me-please-make-this-at-least-32-bytes-long-secret}") secret: String,
    @Value("\${signal-desk.jwt.expiration-hours:720}") private val expirationHours: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.padEnd(32, 'x').toByteArray())

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
}
