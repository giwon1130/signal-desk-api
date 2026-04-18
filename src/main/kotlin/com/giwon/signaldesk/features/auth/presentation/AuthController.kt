package com.giwon.signaldesk.features.auth.presentation

import com.giwon.signaldesk.features.auth.application.AuthException
import com.giwon.signaldesk.features.auth.application.AuthService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.context.annotation.Conditional
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import com.giwon.signaldesk.features.workspace.application.JdbcStoreCondition

data class SignupRequest(
    @field:Email    val email: String,
    @field:NotBlank val password: String,
    @field:NotBlank val nickname: String,
)

data class LoginRequest(
    @field:Email    val email: String,
    @field:NotBlank val password: String,
)

data class GoogleOAuthRequest(@field:NotBlank val idToken: String)
data class KakaoOAuthRequest(@field:NotBlank val accessToken: String)

data class AuthResponse(
    val token: String,
    val userId: String,
    val email: String,
    val nickname: String,
)

@RestController
@RequestMapping("/auth")
@Conditional(JdbcStoreCondition::class)
class AuthController(private val authService: AuthService) {

    @PostMapping("/signup")
    fun signup(@Valid @RequestBody req: SignupRequest): ResponseEntity<AuthResponse> =
        ResponseEntity.ok(authService.signup(req.email, req.password, req.nickname).toResponse())

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): ResponseEntity<AuthResponse> =
        ResponseEntity.ok(authService.login(req.email, req.password).toResponse())

    @GetMapping("/me")
    fun me(@RequestHeader("Authorization") authorization: String): ResponseEntity<AuthResponse> {
        val token = authorization.removePrefix("Bearer ").trim()
        return ResponseEntity.ok(authService.me(token).toResponse())
    }

    @PostMapping("/oauth/google")
    fun googleOAuth(@Valid @RequestBody req: GoogleOAuthRequest): ResponseEntity<AuthResponse> =
        ResponseEntity.ok(authService.googleOAuth(req.idToken).toResponse())

    @PostMapping("/oauth/kakao")
    fun kakaoOAuth(@Valid @RequestBody req: KakaoOAuthRequest): ResponseEntity<AuthResponse> =
        ResponseEntity.ok(authService.kakaoOAuth(req.accessToken).toResponse())

    private fun AuthService.AuthResult.toResponse() =
        AuthResponse(token, userId, email, nickname)
}

@RestControllerAdvice
class AuthExceptionHandler {
    @ExceptionHandler(AuthException::class)
    fun handle(e: AuthException) =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "auth error")))
}
