package com.giwon.signaldesk.features.system.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.GeminiClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 시스템 헬스 — 외부 의존성 (Gemini 등) 의 일시 장애 상태를 앱에 노출.
 * 인증 불필요 (장애 안내는 모든 사용자 대상).
 */
@RestController
@RequestMapping("/api/v1/system")
class SystemStatusController(
    private val gemini: GeminiClient,
) {
    @GetMapping("/status")
    fun status(): ApiResponse<SystemStatusResponse> {
        return ApiResponse(true, SystemStatusResponse(
            gemini = GeminiHealthResponse(
                healthy = gemini.isHealthy(),
                lastFailureAt = gemini.lastFailureAt()?.toString(),
            ),
        ))
    }
}

data class SystemStatusResponse(
    val gemini: GeminiHealthResponse,
)

data class GeminiHealthResponse(
    /** true = 정상 또는 5분 내 실패 없음. false = 최근 5분 내 모든 모델 fallback 실패. */
    val healthy: Boolean,
    /** 마지막 실패 시각 (ISO-8601). healthy=true 면 null. */
    val lastFailureAt: String?,
)
