package com.giwon.signaldesk.bootstrap

import io.jsonwebtoken.JwtException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.format.DateTimeParseException

/** 인증 필요 → 401. */
class UnauthorizedException(message: String = "로그인이 필요합니다.") : RuntimeException(message)

/** 권한 없음 → 403. */
class ForbiddenException(message: String = "권한이 없습니다.") : RuntimeException(message)

/** 리소스 없음 → 404. */
class NotFoundException(message: String = "찾을 수 없습니다.") : RuntimeException(message)

/**
 * 전역 예외 핸들러 — 클라이언트에 raw 500 / "Internal Server Error" 가 새지 않게 한다.
 *
 * 모든 응답은 `{"error": "<사람이 읽을 메시지>"}` 형태. 프론트는 이 본문 텍스트로 사유를 매핑한다.
 * - 검증 실패(require/error = IllegalArgument/IllegalState) → 400
 * - 인증/토큰 문제 → 401, 권한 → 403, 없음 → 404
 * - 요청 형식(enum/날짜/JSON/@Valid) 오류 → 400 (사람이 읽을 메시지로)
 * - 그 외 예상 못한 모든 것 → 500 + 친절 메시지 (스택은 로그로만)
 */
@RestControllerAdvice
class ValidationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun body(message: String?) = mapOf("error" to (message ?: "요청을 처리하지 못했습니다."))

    // ─── 인증/권한 ───────────────────────────────────────────────────────────
    // AuthException 도 여기서 401 — AuthController 의 로컬 advice 와 별개로, 다른 컨트롤러가
    // requireUserId 를 쓸 때 advice 순서에 따라 catch-all 500 으로 떨어지던 것 방지.
    @ExceptionHandler(
        UnauthorizedException::class,
        com.giwon.signaldesk.features.auth.application.AuthException::class,
        JwtException::class,
        MissingRequestHeaderException::class,
    )
    fun unauthorized(e: Exception): ResponseEntity<Map<String, String>> {
        val msg = when (e) {
            is UnauthorizedException -> e.message
            is com.giwon.signaldesk.features.auth.application.AuthException -> e.message
            else -> "로그인이 필요합니다."
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body(msg))
    }

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(e: ForbiddenException) =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(e.message))

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(e.message))

    /** 매칭되는 핸들러가 없는 경로 — catch-all 500에 삼키지 않고 404로. */
    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun unknownPath(e: Exception): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("요청한 경로를 찾을 수 없습니다."))

    // ─── 검증/요청 형식 → 400 ────────────────────────────────────────────────
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun validation(e: RuntimeException): ResponseEntity<Map<String, String>> {
        log.warn("request rejected (validation): {}", e.message)
        return ResponseEntity.badRequest().body(body(e.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun beanValidation(e: MethodArgumentNotValidException): ResponseEntity<Map<String, String>> {
        val first = e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: "입력값을 확인해 주세요."
        return ResponseEntity.badRequest().body(body(first))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun constraint(e: ConstraintViolationException): ResponseEntity<Map<String, String>> {
        val first = e.constraintViolations.firstOrNull()?.message ?: "입력값을 확인해 주세요."
        return ResponseEntity.badRequest().body(body(first))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, DateTimeParseException::class)
    fun malformed(e: Exception): ResponseEntity<Map<String, String>> {
        log.warn("request rejected (malformed): {}", e.message)
        return ResponseEntity.badRequest().body(body("요청 형식이 올바르지 않습니다."))
    }

    // ─── 예상 못한 모든 것 → 500 (스택은 로그만, 사용자에겐 친절 메시지) ────────
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body("일시적인 오류입니다. 잠시 후 다시 시도해 주세요."))
    }
}
