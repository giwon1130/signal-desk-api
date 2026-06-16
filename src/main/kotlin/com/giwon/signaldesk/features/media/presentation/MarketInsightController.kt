package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.MarketInsightAnalysis
import com.giwon.signaldesk.features.media.application.MarketInsightService
import org.springframework.cache.CacheManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/insights")
class MarketInsightController(
    private val service: MarketInsightService,
    private val authContext: AuthContext,
    private val adminGuard: AdminGuard,
    private val cacheManager: CacheManager,
) {

    @GetMapping("/today")
    fun getToday(): ApiResponse<MarketInsightResponse?> {
        val result = service.getTodayInsight()
        return ApiResponse(true, result?.let(MarketInsightResponse::from))
    }

    /**
     * 운영용 캐시 강제 갱신 — 운영자 전용(Gemini 재호출, 쿼터 보호).
     * 인증을 먼저 통과한 뒤에 캐시를 비우고 재계산(미인증자가 캐시를 비우지 못하게 — 어노테이션 evict 는 메서드 진입 전에 돌아 불가).
     */
    @PostMapping("/today/refresh")
    fun refresh(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<MarketInsightResponse?> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        cacheManager.getCache("market-insight")?.clear()
        return getToday()
    }
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
