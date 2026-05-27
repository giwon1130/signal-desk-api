package com.giwon.signaldesk.features.league.presentation

import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.domain.LeagueVisibility
import com.giwon.signaldesk.features.league.domain.MarketScope
import com.giwon.signaldesk.features.league.domain.Participant
import com.giwon.signaldesk.features.league.domain.TradingHours
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateLeagueRequest(
    @field:NotBlank @field:Size(max = 60) val name: String,
    val marketScope: MarketScope,
    val currency: LeagueCurrency,
    val startingCapital: Long,
    val startedAt: String,        // ISO-8601
    val endsAt: String,
    val tradingHours: TradingHours = TradingHours.MARKET_HOURS_ONLY,
    val visibility: LeagueVisibility = LeagueVisibility.OPEN,
    @field:NotBlank @field:Size(max = 20) val hostNickname: String,
    val hostAvatarEmoji: String = "🐱",
)

data class JoinLeagueRequest(
    @field:NotBlank @field:Size(min = 4, max = 8) val joinCode: String,
    @field:NotBlank @field:Size(max = 20) val nickname: String,
    val avatarEmoji: String = "🐱",
)

data class LeagueResponse(
    val id: String,
    val name: String,
    val hostUserId: String,
    val joinCode: String,
    val marketScope: MarketScope,
    val currency: LeagueCurrency,
    val startingCapital: Long,
    val startedAt: String,
    val endsAt: String,
    val status: LeagueStatus,
    val tradingHours: TradingHours,
    val visibility: LeagueVisibility,
    val createdAt: String,
) {
    companion object {
        fun from(l: League) = LeagueResponse(
            id = l.id.toString(), name = l.name, hostUserId = l.hostUserId.toString(),
            joinCode = l.joinCode, marketScope = l.marketScope, currency = l.currency,
            startingCapital = l.startingCapital,
            startedAt = l.startedAt.toString(), endsAt = l.endsAt.toString(),
            status = l.status, tradingHours = l.tradingHours, visibility = l.visibility,
            createdAt = l.createdAt.toString(),
        )
    }
}

data class ParticipantResponse(
    val userId: String,
    val nickname: String,
    val avatarEmoji: String,
    val cashBalance: Long,
    val joinedAt: String,
    val finalReturnRate: Double?,
    val finalRank: Int?,
) {
    companion object {
        fun from(p: Participant) = ParticipantResponse(
            userId = p.userId.toString(),
            nickname = p.nickname, avatarEmoji = p.avatarEmoji,
            cashBalance = p.cashBalance, joinedAt = p.joinedAt.toString(),
            finalReturnRate = p.finalReturnRate?.toDouble(), finalRank = p.finalRank,
        )
    }
}

data class LeagueDetailResponse(
    val league: LeagueResponse,
    val participants: List<ParticipantResponse>,
)
