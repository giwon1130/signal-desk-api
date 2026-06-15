package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.FlowReadingService
import com.giwon.signaldesk.features.media.application.MediaSource
import com.giwon.signaldesk.features.media.application.MediaSummary
import com.giwon.signaldesk.features.media.application.MediaSummaryRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AI 시황 흐름 리딩방 — 리딩 탭의 "🤖 AI 시황" 룸.
 *   GET  /api/v1/reading/ai-flow            — 최신 흐름 리딩 N건
 *   POST /api/v1/reading/ai-flow/refresh    — 수동 생성 (운영/테스트). slot=PREOPEN|CLOSE
 */
@RestController
@RequestMapping("/api/v1/reading/ai-flow")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class FlowReadingController(
    private val repository: MediaSummaryRepository,
    private val service: FlowReadingService,
) {
    data class FlowReadingResponse(
        val id: String,
        val title: String,          // "6월 15일 마감 시황 흐름"
        val headline: String,       // AI 한 줄 요약
        val narrative: String,      // 본문 내러티브
        val flowPoints: List<String>, // 주도/순환매/전망/체크포인트 불릿
        val sentiment: String,
        val keyTickers: List<String>,
        val generatedAt: String,
    ) {
        companion object {
            fun from(m: MediaSummary): FlowReadingResponse {
                // buildSummary 가 summary = "headline\n\nnarrative" 로 저장 → 분리.
                val parts = m.summary.split("\n\n", limit = 2)
                val headline = parts.getOrNull(0)?.trim().orEmpty()
                val narrative = parts.getOrNull(1)?.trim().orEmpty().ifBlank { headline }
                val points = m.flowAnalysis.split("\n")
                    .map { it.removePrefix("•").trim() }
                    .filter { it.isNotBlank() }
                return FlowReadingResponse(
                    id = m.id,
                    title = m.videoTitle,
                    headline = headline,
                    narrative = narrative,
                    flowPoints = points,
                    sentiment = m.sentiment.name,
                    keyTickers = m.keyTickers,
                    generatedAt = m.publishedAt.toString(),
                )
            }
        }
    }

    @GetMapping
    fun recent(@RequestParam(defaultValue = "20") limit: Int): ApiResponse<List<FlowReadingResponse>> {
        val items = repository.findRecentBySource(MediaSource.FLOW_READING, limit.coerceIn(1, 50))
            .map(FlowReadingResponse::from)
        return ApiResponse(true, items)
    }

    /** 수동 생성 — 운영/테스트용. slot=PREOPEN|CLOSE, force=true 면 같은 날 재생성. */
    @PostMapping("/refresh")
    fun refresh(
        @RequestParam(defaultValue = "CLOSE") slot: String,
        @RequestParam(defaultValue = "true") force: Boolean,
    ): ApiResponse<Boolean> {
        val parsed = runCatching { FlowReadingService.Slot.valueOf(slot.uppercase()) }
            .getOrDefault(FlowReadingService.Slot.CLOSE)
        return ApiResponse(true, service.runFlow(parsed, force) != null)
    }
}
