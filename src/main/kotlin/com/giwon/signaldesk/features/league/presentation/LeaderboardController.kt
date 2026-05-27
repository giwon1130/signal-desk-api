package com.giwon.signaldesk.features.league.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.league.application.LeaderboardEntry
import com.giwon.signaldesk.features.league.application.LeaderboardService
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/league/{leagueId}/leaderboard")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class LeaderboardController(
    private val leaderboard: LeaderboardService,
    private val authContext: AuthContext,
) {
    @GetMapping
    fun get(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leagueId: String,
    ): ApiResponse<List<LeaderboardEntryResponse>> {
        authContext.optionalUserId(auth) ?: error("auth required")
        val list = leaderboard.entries(UUID.fromString(leagueId))
        return ApiResponse(true, list.map(LeaderboardEntryResponse::from))
    }
}

data class LeaderboardEntryResponse(
    val userId: String,
    val nickname: String,
    val avatarEmoji: String,
    val cashBalance: Long,
    val evaluatedAssets: Long,
    val totalAssets: Long,
    val returnRate: Double,
    val rank: Int,
    val positionCount: Int,
) {
    companion object {
        fun from(e: LeaderboardEntry) = LeaderboardEntryResponse(
            userId = e.userId.toString(), nickname = e.nickname, avatarEmoji = e.avatarEmoji,
            cashBalance = e.cashBalance, evaluatedAssets = e.evaluatedAssets,
            totalAssets = e.totalAssets, returnRate = e.returnRate,
            rank = e.rank, positionCount = e.positionCount,
        )
    }
}
