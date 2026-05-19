package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.MarketInsightAnalysis
import com.giwon.signaldesk.features.media.application.MarketInsightService
import com.giwon.signaldesk.features.media.application.MediaSentiment
import org.springframework.cache.annotation.CacheEvict
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/insights")
class MarketInsightController(private val service: MarketInsightService) {

    @GetMapping("/today")
    fun getToday(): ApiResponse<MarketInsightResponse?> {
        val result = service.getTodayInsight()
        return ApiResponse(true, result?.let(MarketInsightResponse::from))
    }

    /** 운영용 캐시 강제 갱신 — Gemini 재호출 */
    @PostMapping("/today/refresh")
    @CacheEvict("market-insight", allEntries = true)
    fun refresh(): ApiResponse<MarketInsightResponse?> = getToday()
}

data class MarketInsightResponse(
    val headline: String,
    val summary: String,
    val sentiment: String,
    val keyPoints: List<String>,
) {
    companion object {
        fun from(a: MarketInsightAnalysis) = MarketInsightResponse(
            headline = a.headline,
            summary = a.summary,
            sentiment = a.sentiment.name,
            keyPoints = a.keyPoints,
        )
    }
}
