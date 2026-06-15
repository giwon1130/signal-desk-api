package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.FlowReadingService
import com.giwon.signaldesk.features.media.application.YoutubeFlowReadingService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AI 흐름 리딩 — 수동 생성 트리거(운영자 전용). 평상시엔 스케줄러가 생성하고,
 * 콘텐츠는 리딩 피드(AI 리더 구독)로 전달된다. (조회 엔드포인트는 없음 — 피드로 통합)
 */
@RestController
@RequestMapping("/api/v1/reading/ai-flow")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class FlowReadingController(
    private val service: FlowReadingService,
    private val youtubeService: YoutubeFlowReadingService,
    private val reportCallService: com.giwon.signaldesk.features.reading.application.ReportCallService,
    private val authContext: AuthContext,
    private val adminGuard: AdminGuard,
) {
    /** 데이터 시황 수동 생성 — 운영자 전용. slot=PREOPEN|CLOSE. */
    @PostMapping("/refresh")
    fun refresh(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(defaultValue = "CLOSE") slot: String,
        @RequestParam(defaultValue = "true") force: Boolean,
    ): ApiResponse<Boolean> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        val parsed = runCatching { FlowReadingService.Slot.valueOf(slot.uppercase()) }
            .getOrDefault(FlowReadingService.Slot.CLOSE)
        return ApiResponse(true, service.runFlow(parsed, force) != null)
    }

    /** 유튜브 방송 요약 수동 생성 — 운영자 전용(Supadata 키 필요). 처리 건수 반환. */
    @PostMapping("/refresh-youtube")
    fun refreshYoutube(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(defaultValue = "true") force: Boolean,
    ): ApiResponse<Int> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        return ApiResponse(true, youtubeService.runAll(force))
    }

    /** 📈 AI 리포트 콜 수동 생성 — 운영자 전용. 발행 건수 반환. */
    @PostMapping("/refresh-report")
    fun refreshReport(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(defaultValue = "true") force: Boolean,
    ): ApiResponse<Int> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        return ApiResponse(true, reportCallService.run(force))
    }
}
