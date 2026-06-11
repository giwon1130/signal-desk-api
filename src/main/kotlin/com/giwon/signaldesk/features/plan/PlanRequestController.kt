package com.giwon.signaldesk.features.plan

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * PRO 신청 (사용자) — 인증 필수.
 *   POST /api/v1/plan/request — 신청 (이미 PRO 면 안내만)
 *   GET  /api/v1/plan/request — 내 신청 상태 (PENDING/APPROVED/DISMISSED/null)
 */
@RestController
@RequestMapping("/api/v1/plan")
class PlanRequestController(
    @Autowired(required = false) private val service: PlanRequestService? = null,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    @PostMapping("/request")
    fun request(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<String> {
        val svc = service ?: return ApiResponse(false, "준비되지 않았어요.")
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, "로그인이 필요합니다.")
        return ApiResponse(true, svc.request(userId))
    }

    @GetMapping("/request")
    fun myStatus(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<PlanRequestService.MyRequest?> {
        val svc = service ?: return ApiResponse(false, null)
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, null)
        return ApiResponse(true, svc.myStatus(userId))
    }
}
