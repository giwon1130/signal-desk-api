package com.giwon.signaldesk.bootstrap

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * require()/error() 기반 검증 실패(IllegalArgument/IllegalState)를 500 대신 400 + 메시지로 변환.
 *
 * 리그 거래(시장 시간/현금 부족/수량 초과), 리그 참가(만석/종료/코드없음), 리딩 구독/게시 등
 * 도메인 검증이 전부 require/error 로 던져지는데, 전역 핸들러가 없으면 Spring 기본 500 으로 떨어져
 * 클라이언트가 사유를 읽지 못한다(프론트는 응답 본문 텍스트로 사유를 매핑함).
 *
 * 예: "market KR is closed ..." → 400 {"error": "..."} → 앱이 "시장 거래 시간 아님" 표시.
 */
@RestControllerAdvice
class ValidationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun handle(e: RuntimeException): ResponseEntity<Map<String, String>> {
        log.warn("request rejected (validation): {}", e.message)
        return ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "invalid request")))
    }
}
