package com.giwon.signaldesk.features.ai.presentation

import com.giwon.signaldesk.features.ai.application.AiPickService
import com.giwon.signaldesk.features.ai.application.AiPicksResponse
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/ai")
class AiPickController(
    private val aiPickService: AiPickService,
) {

    /** 오늘의 AI 픽. Gemini 미설정/후보 없음이면 data=null. */
    @GetMapping("/picks")
    fun picks(): ApiResponse<AiPicksResponse?> =
        ApiResponse(true, aiPickService.getTodayPicks())
}
