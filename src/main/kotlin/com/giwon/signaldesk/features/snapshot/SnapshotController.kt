package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 운영자 전용. 시장별 캡처 시간/거래일 검증을 수동 실행에도 동일 적용한다. */
@RestController
@RequestMapping("/api/v1/snapshots")
class SnapshotController(
    private val authContext: AuthContext,
    private val adminGuard: AdminGuard,
    @Autowired(required = false) private val service: DailySnapshotService? = null,
) {
    @PostMapping("/run")
    fun run(@RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(defaultValue = "KR") market: String = "KR"): ApiResponse<DailySnapshotService.Result?> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        require(market in setOf("KR", "US")) { "market must be KR or US" }
        val svc = service ?: return ApiResponse(false, null)
        return ApiResponse(true, svc.runDailySnapshot(market))
    }
}
