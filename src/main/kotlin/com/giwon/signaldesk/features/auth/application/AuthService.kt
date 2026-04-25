package com.giwon.signaldesk.features.auth.application

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Conditional
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import com.giwon.signaldesk.features.workspace.application.JdbcStoreCondition
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class AuthException(message: String) : RuntimeException(message)

@Service
@Conditional(JdbcStoreCondition::class)
class AuthService(
    private val userRepo: UserRepository,
    private val jwt: JwtProvider,
    @Value("\${signal-desk.auth.google.allowed-audiences:}") private val rawGoogleAllowedAudiences: String,
) {
    private val encoder = BCryptPasswordEncoder()
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * 화이트리스트된 Google OAuth audience(클라이언트 ID) 목록.
     * 환경변수 `SIGNAL_DESK_AUTH_GOOGLE_ALLOWED_AUDIENCES` 콤마 구분.
     *  · 비어있으면 audience 검증 건너뜀(과거 호환). 운영에선 반드시 채울 것.
     *  · 채워져 있으면 token.aud 가 이 목록에 없을 때 검증 실패.
     */
    private val googleAllowedAudiences: Set<String> = rawGoogleAllowedAudiences
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    @PostConstruct
    fun warnIfAudienceMissing() {
        if (googleAllowedAudiences.isEmpty()) {
            logger.warn("⚠️  [SECURITY] signal-desk.auth.google.allowed-audiences 미설정 — 모든 Google 클라이언트 ID 의 토큰을 통과시킴. 운영에선 반드시 화이트리스트 등록.")
        } else {
            logger.info("Google audience whitelist active: ${googleAllowedAudiences.size} entries")
        }
    }

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
        logger.info("signup user={} method=email", user.id.toString().take(8))
        return user.toResult()
    }

    fun login(email: String, password: String): AuthResult {
        val user = userRepo.findByEmail(email.trim().lowercase())
            ?: run {
                logger.info("login fail reason=user-not-found")
                throw AuthException("이메일 또는 비밀번호가 틀렸어요.")
            }
        val hash = user.passwordHash ?: run {
            logger.info("login fail user={} reason=oauth-only", user.id.toString().take(8))
            throw AuthException("이 계정은 소셜 로그인으로 가입됐어요.")
        }
        if (!encoder.matches(password, hash)) {
            logger.info("login fail user={} reason=bad-password", user.id.toString().take(8))
            throw AuthException("이메일 또는 비밀번호가 틀렸어요.")
        }
        logger.info("login success user={} method=email", user.id.toString().take(8))
        return user.toResult()
    }

    fun me(token: String): AuthResult {
        val userId = jwt.extractUserId(token)
        val user = userRepo.findById(userId) ?: throw AuthException("유저를 찾을 수 없어요.")
        return user.toResult(token)
    }

    // ── Google OAuth ──────────────────────────────────────────────────────────

    fun googleOAuth(idToken: String): AuthResult {
        val info = verifyGoogleToken(idToken) ?: run {
            logger.info("oauth fail provider=google reason=invalid-token")
            throw AuthException("유효하지 않은 구글 토큰이에요.")
        }
        val existing = userRepo.findByGoogleId(info.id)
        val byEmail = if (existing == null) userRepo.findByEmail(info.email) else null
        val user = existing
            ?: byEmail?.also {
                userRepo.linkGoogleId(it.id, info.id)
                logger.info("oauth link provider=google user={} email={}", it.id.toString().take(8), info.email)
            }
            ?: userRepo.saveOAuthUser(
                email    = info.email,
                nickname = info.name.ifBlank { info.email.substringBefore("@") },
                googleId = info.id,
            ).also { logger.info("signup user={} method=google", it.id.toString().take(8)) }
        logger.info("login success user={} method=google", user.id.toString().take(8))
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
            .timeout(Duration.ofSeconds(5))
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return null
        val node = mapper.readTree(res.body())
        // audience 검증 — 화이트리스트가 채워져 있으면 token.aud 가 그 안에 있어야 함.
        // 비어있으면 검증 스킵 (개발 편의 + 과거 동작 호환). 운영에선 채워야 안전.
        if (googleAllowedAudiences.isNotEmpty()) {
            val aud = node.get("aud")?.asText()
            if (aud == null || aud !in googleAllowedAudiences) {
                logger.warn("Google OAuth: audience '$aud' 가 화이트리스트에 없음 → 거부")
                return null
            }
        }
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
            .timeout(Duration.ofSeconds(5))
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
