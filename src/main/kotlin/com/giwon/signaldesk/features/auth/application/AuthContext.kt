package com.giwon.signaldesk.features.auth.application

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 컨트롤러에서 Authorization 헤더로부터 userId 추출.
 *
 * 사용 예:
 *   @GetMapping("/watchlist")
 *   fun list(@RequestHeader("Authorization", required = false) auth: String?): ... {
 *       val userId = authContext.requireUserId(auth)   // 토큰 없으면 401
 *       ...
 *   }
 */
@Component
class AuthContext(private val jwt: JwtProvider) {

    fun optionalUserId(authorization: String?): UUID? {
        val token = authorization?.removePrefix("Bearer ")?.trim().orEmpty()
        if (token.isBlank() || !jwt.isValid(token)) return null
        return jwt.extractUserId(token)
    }

    fun requireUserId(authorization: String?): UUID =
        optionalUserId(authorization) ?: throw AuthException("로그인이 필요해요.")
}
