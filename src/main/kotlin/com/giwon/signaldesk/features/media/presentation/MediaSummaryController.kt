package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.IntradayBriefService
import com.giwon.signaldesk.features.media.application.MediaSource
import com.giwon.signaldesk.features.media.application.MediaSummaryRepository
import com.giwon.signaldesk.features.media.application.MorningBriefService
import com.giwon.signaldesk.features.media.application.NewsDigestService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 미디어(모닝 브리프 / 뉴스 종합) API.
 *
 * 모든 사용자에게 동일한 결과 — 개인화 X. (시장 종합 컨텐츠는 공통)
 * 푸시 본문에서만 보유 종목 prefix 로 개인화.
 */
@RestController
@RequestMapping("/api/v1/media")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MediaSummaryController(
    private val repository: MediaSummaryRepository,
    private val newsDigestService: NewsDigestService,
    private val morningBriefService: MorningBriefService,
    private val intradayBriefService: IntradayBriefService,
) {

    @GetMapping("/summaries/latest")
    fun getLatest(): ApiResponse<MediaSummaryResponse?> {
        val latest = repository.findRecent(1).firstOrNull()?.let(MediaSummaryResponse::from)
        return ApiResponse(true, latest)
    }

    /** 최근 미디어 요약 N건 — 오늘 탭 브리프 카드 회전용 (모닝/이브닝 브리프·뉴스 종합 등 다양하게). */
    @GetMapping("/summaries/recent")
    fun getRecent(@RequestParam(defaultValue = "6") limit: Int): ApiResponse<List<MediaSummaryResponse>> {
        val items = repository.findRecent(limit.coerceIn(1, 20)).map(MediaSummaryResponse::from)
        return ApiResponse(true, items)
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

    /** 장중/마감 브리프 수동 트리거 — 운영/테스트용. slot=MIDDAY|CLOSE. */
    @PostMapping("/intraday-brief/refresh")
    fun refreshIntradayBrief(
        @RequestParam(defaultValue = "MIDDAY") slot: String,
        @RequestParam(defaultValue = "false") force: Boolean,
    ): ApiResponse<Boolean> {
        val parsed = runCatching { IntradayBriefService.Slot.valueOf(slot.uppercase()) }
            .getOrDefault(IntradayBriefService.Slot.MIDDAY)
        val brief = intradayBriefService.runBrief(parsed, force)
        return ApiResponse(true, brief != null)
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
