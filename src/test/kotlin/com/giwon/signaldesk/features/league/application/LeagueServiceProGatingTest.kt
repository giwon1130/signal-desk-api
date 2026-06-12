package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.domain.LeagueVisibility
import com.giwon.signaldesk.features.league.domain.MarketScope
import com.giwon.signaldesk.features.league.domain.TradingHours
import com.giwon.signaldesk.features.league.repository.LeagueRepository
import com.giwon.signaldesk.features.league.repository.ParticipantRepository
import com.giwon.signaldesk.features.plan.PlanService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 리그 생성 FREE 상한 — 진행 중(FINISHED 아님) 리그만 카운트.
 */
class LeagueServiceProGatingTest {

    private val leagues = mock(LeagueRepository::class.java)
    private val participants = mock(ParticipantRepository::class.java)
    private val planService = mock(PlanService::class.java)
    private val service = LeagueService(leagues, participants, planService)

    private val host = UUID.randomUUID()
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-01-02T00:00:00Z")

    private fun league(status: LeagueStatus) = League(
        id = UUID.randomUUID(), name = "L", hostUserId = host, joinCode = "ABC123",
        marketScope = MarketScope.KR, currency = LeagueCurrency.KRW, startingCapital = 1_000_000,
        startedAt = start, endsAt = end, status = status, tradingHours = TradingHours.MARKET_HOURS_ONLY,
        fee = BigDecimal("0.003"), maxPositionPct = BigDecimal("0.30"), visibility = LeagueVisibility.OPEN,
        createdAt = start,
    )

    private fun create() = service.create(
        hostUserId = host, name = "내 리그", marketScope = MarketScope.KR, currency = LeagueCurrency.KRW,
        startingCapital = 1_000_000, startedAt = start, endsAt = end,
        hostNickname = "호스트", hostAvatarEmoji = "🐱",
    )

    @Test
    fun `진행 중 리그만 상한 카운트 — FINISHED 는 제외`() {
        `when`(leagues.findByHost(host)).thenReturn(listOf(league(LeagueStatus.FINISHED), league(LeagueStatus.RUNNING)))

        create()

        // RUNNING 1개만 카운트(FINISHED 제외) → assertCanAdd(.., 1)
        verify(planService).assertCanAdd(host, PlanService.Resource.LEAGUES, 1)
    }

    @Test
    fun `상한 초과 시 생성 거부`() {
        `when`(leagues.findByHost(host)).thenReturn(listOf(league(LeagueStatus.RUNNING)))
        doThrow(IllegalArgumentException("리그 상한")).`when`(planService)
            .assertCanAdd(host, PlanService.Resource.LEAGUES, 1)

        assertThatThrownBy { create() }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
