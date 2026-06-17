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
    private val adminGuard: com.giwon.signaldesk.features.admin.AdminGuard,
    @Value("\${signal-desk.auth.google.allowed-audiences:}") private val rawGoogleAllowedAudiences: String,
    @Value("\${signal-desk.auth.apple.allowed-audiences:com.giwon.signaldesk}") private val rawAppleAllowedAudiences: String,
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

    /** Apple Sign-In audience(앱 번들 ID) 화이트리스트. identityToken.aud 가 이 안에 있어야 함. */
    private val appleAllowedAudiences: Set<String> = rawAppleAllowedAudiences
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @PostConstruct
    fun warnIfAudienceMissing() {
        if (googleAllowedAudiences.isEmpty()) {
            logger.warn("⚠️  [SECURITY] signal-desk.auth.google.allowed-audiences 미설정 — 모든 Google 클라이언트 ID 의 토큰을 통과시킴. 운영에선 반드시 화이트리스트 등록.")
        } else {
            logger.info("Google audience whitelist active: ${googleAllowedAudiences.size} entries")
        }
    }

    data class AuthResult(
        val token: String, val userId: String, val email: String, val nickname: String,
        val plan: String = "FREE",
        /** 운영자 여부 — 웹 운영자 콘솔 노출용. */
        val admin: Boolean = false,
    )

    // ── 이메일/비밀번호 ────────────────────────────────────────────────────────

    fun signup(email: String, password: String, nickname: String): AuthResult {
        if (userRepo.existsByEmail(email)) throw AuthException("이미 사용 중인 이메일입니다.")
        if (password.length < 6) throw AuthException("비밀번호는 6자 이상이어야 합니다.")
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
                throw AuthException("이메일 또는 비밀번호가 틀렸습니다.")
            }
        val hash = user.passwordHash ?: run {
            logger.info("login fail user={} reason=oauth-only", user.id.toString().take(8))
            throw AuthException("이 계정은 소셜 로그인으로 가입됐습니다.")
        }
        if (!encoder.matches(password, hash)) {
            logger.info("login fail user={} reason=bad-password", user.id.toString().take(8))
            throw AuthException("이메일 또는 비밀번호가 틀렸습니다.")
        }
        logger.info("login success user={} method=email", user.id.toString().take(8))
        return user.toResult()
    }

    fun me(token: String): AuthResult {
        val userId = jwt.extractUserId(token)
        val user = userRepo.findById(userId) ?: throw AuthException("유저를 찾을 수 없습니다.")
        return user.toResult(token)
    }

    // ── Google OAuth ──────────────────────────────────────────────────────────

    fun googleOAuth(idToken: String): AuthResult {
        val info = verifyGoogleToken(idToken) ?: run {
            logger.info("oauth fail provider=google reason=invalid-token")
            throw AuthException("유효하지 않은 구글 토큰입니다.")
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
        val info = verifyKakaoToken(accessToken) ?: throw AuthException("유효하지 않은 카카오 토큰입니다.")
        val user = userRepo.findByKakaoId(info.id)
            ?: userRepo.findByEmail(info.email)?.also { userRepo.linkKakaoId(it.id, info.id) }
            ?: userRepo.saveOAuthUser(
                email   = info.email,
                nickname = info.nickname.ifBlank { info.email.substringBefore("@") },
                kakaoId = info.id,
            )
        return user.toResult()
    }

    // ── Apple OAuth (Sign in with Apple) ───────────────────────────────────────

    fun appleOAuth(identityToken: String): AuthResult {
        val info = verifyAppleToken(identityToken) ?: run {
            logger.info("oauth fail provider=apple reason=invalid-token")
            throw AuthException("유효하지 않은 애플 토큰입니다.")
        }
        val user = userRepo.findByAppleId(info.id)
            ?: info.email?.let { em -> userRepo.findByEmail(em)?.also { userRepo.linkAppleId(it.id, info.id) } }
            ?: userRepo.saveOAuthUser(
                email = info.email ?: "apple-${info.id.takeLast(12)}@privaterelay.appleid.com",
                nickname = (info.email?.substringBefore("@")) ?: "Apple 사용자",
                appleId = info.id,
            ).also { logger.info("signup user={} method=apple", it.id.toString().take(8)) }
        logger.info("login success user={} method=apple", user.id.toString().take(8))
        return user.toResult()
    }

    // ── 토큰 검증 헬퍼 ───────────────────────────────────────────────────────

    private data class GoogleUserInfo(val id: String, val email: String, val name: String)
    private data class KakaoUserInfo(val id: String, val email: String, val nickname: String)
    private data class AppleUserInfo(val id: String, val email: String?)

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
        // 이메일 검증 — 미verified 이메일로 기존 계정에 링크되면 탈취 위험. verified 만 허용.
        val ev = node.get("email_verified")
        val emailVerified = ev != null && (ev.asBoolean(false) || ev.asText("") == "true")
        if (!emailVerified) {
            logger.warn("Google OAuth: email_verified=false → 거부 email={}", node.get("email")?.asText())
            return null
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
        // 이메일 검증 — 미verified 이메일로 기존 계정에 링크되면 탈취 위험. verified 만 허용.
        val ev = account.get("is_email_verified")
        if (ev != null && !ev.asBoolean(false)) {
            logger.warn("Kakao OAuth: is_email_verified=false → 거부 email={}", account.get("email")?.asText())
            return null
        }
        KakaoUserInfo(
            id       = node.get("id")?.asText() ?: return null,
            email    = account.get("email")?.asText() ?: return null,
            nickname = account.get("profile")?.get("nickname")?.asText() ?: "",
        )
    }.getOrNull()

    /**
     * Apple identityToken(JWT, RS256) 검증 — Apple JWKS 로 서명 확인 + iss/aud/exp 검증.
     *  1) 토큰 헤더의 kid 로 https://appleid.apple.com/auth/keys 에서 공개키 선택
     *  2) JWK(n,e) → RSAPublicKey 구성 후 jjwt 로 서명·만료 검증
     *  3) iss=https://appleid.apple.com, aud=앱 번들ID 확인 → sub(고유 ID)·email 추출
     */
    private fun verifyAppleToken(identityToken: String): AppleUserInfo? = runCatching {
        val parts = identityToken.split(".")
        if (parts.size < 2) return null
        val header = mapper.readTree(java.util.Base64.getUrlDecoder().decode(parts[0]))
        val kid = header.get("kid")?.asText() ?: return null

        val keysReq = HttpRequest.newBuilder()
            .uri(URI.create("https://appleid.apple.com/auth/keys"))
            .timeout(Duration.ofSeconds(5)).GET().build()
        val keysRes = http.send(keysReq, HttpResponse.BodyHandlers.ofString())
        if (keysRes.statusCode() != 200) return null
        val jwk = mapper.readTree(keysRes.body()).get("keys")
            ?.firstOrNull { it.get("kid")?.asText() == kid } ?: return null
        val n = java.math.BigInteger(1, java.util.Base64.getUrlDecoder().decode(jwk.get("n").asText()))
        val e = java.math.BigInteger(1, java.util.Base64.getUrlDecoder().decode(jwk.get("e").asText()))
        val pub = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.RSAPublicKeySpec(n, e))

        // 서명·exp 검증(jjwt). 실패 시 예외 → runCatching 으로 null.
        val claims = io.jsonwebtoken.Jwts.parser().verifyWith(pub).build()
            .parseSignedClaims(identityToken).payload

        if (claims.issuer != "https://appleid.apple.com") {
            logger.warn("Apple OAuth: 잘못된 iss={}", claims.issuer); return null
        }
        if (appleAllowedAudiences.isNotEmpty() && claims.audience.orEmpty().intersect(appleAllowedAudiences).isEmpty()) {
            logger.warn("Apple OAuth: audience {} 가 화이트리스트에 없음 → 거부", claims.audience); return null
        }
        val sub = claims.subject ?: return null
        val email = runCatching { claims.get("email", String::class.java) }.getOrNull()?.takeIf { it.isNotBlank() }
        AppleUserInfo(id = sub, email = email)
    }.getOrNull()

    // ── 공통 변환 ─────────────────────────────────────────────────────────────

    private fun SignalUser.toResult(existingToken: String? = null): AuthResult {
        val token = existingToken ?: jwt.generate(id, email)
        return AuthResult(token, id.toString(), email, nickname, plan = plan, admin = adminGuard.isAdminEmail(email))
    }
}
