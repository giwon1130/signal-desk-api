package com.giwon.signaldesk.features.auth.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Conditional
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import com.giwon.signaldesk.features.workspace.application.JdbcStoreCondition
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class AuthException(message: String) : RuntimeException(message)

@Service
@Conditional(JdbcStoreCondition::class)
class AuthService(
    private val userRepo: UserRepository,
    private val jwt: JwtProvider,
) {
    private val encoder = BCryptPasswordEncoder()
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    data class AuthResult(val token: String, val userId: String, val email: String, val nickname: String)

    // ── 이메일/비밀번호 ────────────────────────────────────────────────────────

    fun signup(email: String, password: String, nickname: String): AuthResult {
        if (userRepo.existsByEmail(email)) throw AuthException("이미 사용 중인 이메일이에요.")
        if (password.length < 6) throw AuthException("비밀번호는 6자 이상이어야 해요.")
        val user = userRepo.save(
            email        = email.trim().lowercase(),
            passwordHash = encoder.encode(password),
            nickname     = nickname.trim(),
        )
        return user.toResult()
    }

    fun login(email: String, password: String): AuthResult {
        val user = userRepo.findByEmail(email.trim().lowercase())
            ?: throw AuthException("이메일 또는 비밀번호가 틀렸어요.")
        val hash = user.passwordHash ?: throw AuthException("이 계정은 소셜 로그인으로 가입됐어요.")
        if (!encoder.matches(password, hash)) throw AuthException("이메일 또는 비밀번호가 틀렸어요.")
        return user.toResult()
    }

    fun me(token: String): AuthResult {
        val userId = jwt.extractUserId(token)
        val user = userRepo.findById(userId) ?: throw AuthException("유저를 찾을 수 없어요.")
        return user.toResult(token)
    }

    // ── Google OAuth ──────────────────────────────────────────────────────────

    fun googleOAuth(idToken: String): AuthResult {
        val info = verifyGoogleToken(idToken) ?: throw AuthException("유효하지 않은 구글 토큰이에요.")
        val user = userRepo.findByGoogleId(info.id)
            ?: userRepo.findByEmail(info.email)?.also { userRepo.linkGoogleId(it.id, info.id) }
            ?: userRepo.saveOAuthUser(
                email    = info.email,
                nickname = info.name.ifBlank { info.email.substringBefore("@") },
                googleId = info.id,
            )
        return user.toResult()
    }

    // ── Kakao OAuth ───────────────────────────────────────────────────────────

    fun kakaoOAuth(accessToken: String): AuthResult {
        val info = verifyKakaoToken(accessToken) ?: throw AuthException("유효하지 않은 카카오 토큰이에요.")
        val user = userRepo.findByKakaoId(info.id)
            ?: userRepo.findByEmail(info.email)?.also { userRepo.linkKakaoId(it.id, info.id) }
            ?: userRepo.saveOAuthUser(
                email   = info.email,
                nickname = info.nickname.ifBlank { info.email.substringBefore("@") },
                kakaoId = info.id,
            )
        return user.toResult()
    }

    // ── 토큰 검증 헬퍼 ───────────────────────────────────────────────────────

    private data class GoogleUserInfo(val id: String, val email: String, val name: String)
    private data class KakaoUserInfo(val id: String, val email: String, val nickname: String)

    private fun verifyGoogleToken(idToken: String): GoogleUserInfo? = runCatching {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://oauth2.googleapis.com/tokeninfo?id_token=$idToken"))
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return null
        val node = mapper.readTree(res.body())
        GoogleUserInfo(
            id    = node.get("sub")?.asText() ?: return null,
            email = node.get("email")?.asText() ?: return null,
            name  = node.get("name")?.asText() ?: "",
        )
    }.getOrNull()

    private fun verifyKakaoToken(accessToken: String): KakaoUserInfo? = runCatching {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://kapi.kakao.com/v2/user/me"))
            .header("Authorization", "Bearer $accessToken")
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return null
        val node = mapper.readTree(res.body())
        val account = node.get("kakao_account") ?: return null
        KakaoUserInfo(
            id       = node.get("id")?.asText() ?: return null,
            email    = account.get("email")?.asText() ?: return null,
            nickname = account.get("profile")?.get("nickname")?.asText() ?: "",
        )
    }.getOrNull()

    // ── 공통 변환 ─────────────────────────────────────────────────────────────

    private fun SignalUser.toResult(existingToken: String? = null): AuthResult {
        val token = existingToken ?: jwt.generate(id, email)
        return AuthResult(token, id.toString(), email, nickname)
    }
}
