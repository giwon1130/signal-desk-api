package com.giwon.signaldesk.features.fortune.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.fortune.application.DailyFortune
import com.giwon.signaldesk.features.fortune.application.FortuneService
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/workspace/fortune  —  오늘의 투자 운세.
 *
 * 비로그인 상태(AuthContext 없거나 토큰 없음)에도 호출 가능하며, 그 경우 익명 시드로 공통 운세를 돌려준다.
 */
@RestController
@RequestMapping("/api/v1/workspace")
class FortuneController(
    private val fortuneService: FortuneService,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {

    @GetMapping("/fortune")
    fun getFortune(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<DailyFortune> {
        val userId = authContext?.optionalUserId(auth)
        return ApiResponse(true, fortuneService.today(userId))
    }
}
