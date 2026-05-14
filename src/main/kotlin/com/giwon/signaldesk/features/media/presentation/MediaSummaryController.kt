package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.MediaSummaryRepository
import com.giwon.signaldesk.features.media.application.MediaSummaryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 유튜브 데일리 방송 요약 API.
 *
 * 모든 사용자에게 동일한 결과 — 개인화 X. (방송은 공통 컨텐츠라서)
 */
@RestController
@RequestMapping("/api/v1/media")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MediaSummaryController(
    private val repository: MediaSummaryRepository,
    private val service: MediaSummaryService,
) {

    @GetMapping("/summaries")
    fun getRecent(@RequestParam(defaultValue = "10") limit: Int): ApiResponse<List<MediaSummaryResponse>> {
        val items = repository.findRecent(limit.coerceIn(1, 30)).map(MediaSummaryResponse::from)
        return ApiResponse(true, items)
    }

    @GetMapping("/summaries/latest")
    fun getLatest(): ApiResponse<MediaSummaryResponse?> {
        val latest = repository.findRecent(1).firstOrNull()?.let(MediaSummaryResponse::from)
        return ApiResponse(true, latest)
    }

    @GetMapping("/summaries/{id}")
    fun getById(@PathVariable id: String): ApiResponse<MediaSummaryResponse?> {
        val item = repository.findById(id)?.let(MediaSummaryResponse::from)
        return ApiResponse(true, item)
    }

    /** 수동 트리거 — 운영용. 실행 결과 처리된 영상 수 반환. */
    @PostMapping("/summaries/refresh")
    fun refresh(): ApiResponse<Int> {
        val processed = service.runDailyScan()
        return ApiResponse(true, processed)
    }
}
