package com.giwon.signaldesk.features.reading.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.reading.application.ReadingFeedService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 리딩 피드 + 리더 프로필/성과 조회 (Phase D).
 */
@RestController
@RequestMapping("/api/v1/reading")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class ReadingFeedController(
    private val feed: ReadingFeedService,
    private val authContext: AuthContext,
) {
    private fun requireUserId(auth: String?): UUID =
        authContext.optionalUserId(auth) ?: error("auth required")

    private fun toPostResponse(v: ReadingFeedService.PostWithCalls) =
        PostResponse.from(v.post, v.leaderName, v.calls.map { CallResponse.from(it) })

    /** 내 피드 — 구독 리더 + 본인 글. */
    @GetMapping("/feed")
    fun feed(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ApiResponse<List<PostResponse>> {
        val userId = requireUserId(auth)
        return ApiResponse(true, feed.feed(userId, limit).map(::toPostResponse))
    }

    /** 리딩 둘러보기 — 승인된 리더 목록(통계·구독여부). 신규 사용자 발견성. */
    @GetMapping("/leaders")
    fun discover(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<List<LeaderCardResponse>> {
        val viewerId = authContext.optionalUserId(auth)
        return ApiResponse(true, feed.discoverLeaders(viewerId).map(LeaderCardResponse::from))
    }

    /** 리더 프로필 — 통계 + 글 목록. */
    @GetMapping("/leader/{leaderUserId}/profile")
    fun profile(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leaderUserId: String,
    ): ApiResponse<LeaderProfileResponse?> {
        requireUserId(auth)
        val id = UUID.fromString(leaderUserId)
        val leader = feed.getLeader(id) ?: return ApiResponse(true, null)
        val stats = feed.leaderStats(id)
        val posts = feed.leaderPosts(id).map(::toPostResponse)
        return ApiResponse(
            true,
            LeaderProfileResponse(
                leader = LeaderResponse.from(leader, feed.followerCount(id), includeCode = false),
                stats = LeaderStatsResponse.from(stats),
                posts = posts,
            ),
        )
    }
}

data class LeaderStatsResponse(
    val totalCalls: Int,
    val hitCount: Int,
    val hitRate: Double,
    val avgReturnPct: Double?,
) {
    companion object {
        fun from(s: ReadingFeedService.LeaderStats) =
            LeaderStatsResponse(s.totalCalls, s.hitCount, s.hitRate, s.avgReturnPct)
    }
}

data class LeaderProfileResponse(
    val leader: LeaderResponse,
    val stats: LeaderStatsResponse,
    val posts: List<PostResponse>,
)

data class LeaderCardResponse(
    val userId: String,
    val displayName: String,
    val bio: String,
    val followerCount: Int,
    val totalCalls: Int,
    val hitRate: Double,
    val avgReturnPct: Double?,
    val following: Boolean,
) {
    companion object {
        fun from(c: ReadingFeedService.LeaderCard) = LeaderCardResponse(
            c.userId.toString(), c.displayName, c.bio, c.followerCount,
            c.totalCalls, c.hitRate, c.avgReturnPct, c.following,
        )
    }
}
