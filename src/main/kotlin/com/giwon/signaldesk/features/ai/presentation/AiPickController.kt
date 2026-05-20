package com.giwon.signaldesk.features.ai.presentation

import com.giwon.signaldesk.features.ai.application.AiPickService
import com.giwon.signaldesk.features.ai.application.AiPicksResponse
import com.giwon.signaldesk.features.ai.application.HiddenSignalService
import com.giwon.signaldesk.features.ai.application.HiddenSignalsResponse
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/ai")
class AiPickController(
    private val aiPickService: AiPickService,
    @Autowired(required = false) private val hiddenSignalService: HiddenSignalService? = null,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {

    /** 오늘의 AI 픽. Gemini 미설정/후보 없음이면 data=null. */
    @GetMapping("/picks")
    fun picks(): ApiResponse<AiPicksResponse?> =
        ApiResponse(true, aiPickService.getTodayPicks())

    /** 숨은 시그널 — 보유/관심 종목에 잡힌 공시·수급·급등락. 비로그인이면 빈 리스트. */
    @GetMapping("/signals")
    fun signals(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<HiddenSignalsResponse> {
        val userId = authContext?.optionalUserId(auth)
        val service = hiddenSignalService
        if (userId == null || service == null) {
            return ApiResponse(true, HiddenSignalsResponse(java.time.Instant.now().toString(), emptyList()))
        }
        return ApiResponse(true, service.signalsForUser(userId))
    }
}
