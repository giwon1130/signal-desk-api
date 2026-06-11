package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 일별 스냅샷 수동 트리거 — 운영용 (스케줄 16:40 KST 와 동일 로직, 멱등). */
@RestController
@RequestMapping("/api/v1/snapshots")
class SnapshotController(
    @Autowired(required = false) private val service: DailySnapshotService? = null,
) {
    @PostMapping("/run")
    fun run(): ApiResponse<DailySnapshotService.Result?> {
        val svc = service ?: return ApiResponse(false, null)
        return ApiResponse(true, svc.runDailySnapshot())
    }
}
