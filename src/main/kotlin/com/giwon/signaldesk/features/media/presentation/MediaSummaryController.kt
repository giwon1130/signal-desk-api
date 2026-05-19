package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.MediaSource
import com.giwon.signaldesk.features.media.application.MediaSummaryRepository
import com.giwon.signaldesk.features.media.application.MediaSummaryService
import com.giwon.signaldesk.features.media.application.MorningBriefService
import com.giwon.signaldesk.features.media.application.NewsDigestService
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
    private val newsDigestService: NewsDigestService,
    private val morningBriefService: MorningBriefService,
) {

    @GetMapping("/summaries/latest")
    fun getLatest(): ApiResponse<MediaSummaryResponse?> {
        val latest = repository.findRecent(1).firstOrNull()?.let(MediaSummaryResponse::from)
        return ApiResponse(true, latest)
    }

    /** 오늘의 모닝 브리프 (없으면 null). */
    @GetMapping("/morning-brief")
    fun getMorningBrief(): ApiResponse<MediaSummaryResponse?> {
        val latest = repository.findRecent(20)
            .firstOrNull { it.source == MediaSource.MORNING_BRIEF }
            ?.let(MediaSummaryResponse::from)
        return ApiResponse(true, latest)
    }

    /** 모닝 브리프 수동 트리거 — 운영용. force=true 면 기존 brief 재생성. */
    @PostMapping("/morning-brief/refresh")
    fun refreshMorningBrief(@RequestParam(defaultValue = "false") force: Boolean): ApiResponse<Boolean> {
        val brief = morningBriefService.runBrief(force)
        return ApiResponse(true, brief != null)
    }

    /**
     * 수동 트리거 — 운영용. (YouTube 채널 요약)
     * @param force true 면 이미 처리한 video_id 도 재처리
     */
    @PostMapping("/summaries/refresh")
    fun refresh(@RequestParam(defaultValue = "false") force: Boolean): ApiResponse<Int> {
        val processed = service.runDailyScan(force)
        return ApiResponse(true, processed)
    }

    /**
     * 뉴스 종합 요약 수동 트리거.
     * @param market "KR" or "US". 둘 다 처리하려면 "ALL".
     */
    @PostMapping("/summaries/news-digest")
    fun newsDigest(
        @RequestParam(defaultValue = "KR") market: String,
        @RequestParam(defaultValue = "false") force: Boolean,
    ): ApiResponse<Int> {
        val processed = if (market.uppercase() == "ALL") {
            newsDigestService.runAll(force)
        } else {
            if (newsDigestService.runDigest(market.uppercase(), force) != null) 1 else 0
        }
        return ApiResponse(true, processed)
    }
}
