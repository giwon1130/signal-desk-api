package com.giwon.signaldesk.features.league.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.league.application.LeagueService
import com.giwon.signaldesk.features.league.application.PositionService
import com.giwon.signaldesk.features.league.application.TradeService
import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.domain.LeagueVisibility
import com.giwon.signaldesk.features.league.domain.MarketScope
import com.giwon.signaldesk.features.league.domain.TradingHours
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 동료 보유 드릴다운 엔드포인트의 가시성 가드 — 비공개(CLOSED) 리그에서 타인 보유가 새지 않아야 한다.
 */
class TradeControllerTest {

    private val tradeService = mock(TradeService::class.java)
    private val positionService = mock(PositionService::class.java)
    private val leagueService = mock(LeagueService::class.java)
    private val authContext = mock(AuthContext::class.java)
    private val controller = TradeController(tradeService, positionService, leagueService, authContext)

    private val leagueId = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun league(visibility: LeagueVisibility) = League(
        id = leagueId, name = "테스트", hostUserId = me, joinCode = "ABC123",
        marketScope = MarketScope.KR, currency = LeagueCurrency.KRW, startingCapital = 1_000_000,
        startedAt = Instant.EPOCH, endsAt = Instant.EPOCH, status = LeagueStatus.RUNNING,
        tradingHours = TradingHours.ALWAYS, fee = BigDecimal("0.003"), maxPositionPct = BigDecimal("0.30"),
        visibility = visibility, createdAt = Instant.EPOCH,
    )

    @Test
    fun `공개 리그면 동료 보유를 조회한다`() {
        `when`(authContext.optionalUserId("auth")).thenReturn(me)
        `when`(leagueService.get(leagueId)).thenReturn(league(LeagueVisibility.OPEN))
        `when`(positionService.positionViewsForUser(leagueId, other)).thenReturn(emptyList())

        val res = controller.memberPositions("auth", leagueId.toString(), other.toString())

        assertThat(res.success).isTrue()
        verify(positionService).positionViewsForUser(leagueId, other)
    }

    @Test
    fun `비공개 리그에서 타인 보유 조회는 거부한다`() {
        `when`(authContext.optionalUserId("auth")).thenReturn(me)
        `when`(leagueService.get(leagueId)).thenReturn(league(LeagueVisibility.CLOSED))

        assertThatThrownBy { controller.memberPositions("auth", leagueId.toString(), other.toString()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(positionService, never()).positionViewsForUser(leagueId, other)
    }

    @Test
    fun `비공개 리그라도 본인 보유는 가시성 체크 없이 조회한다`() {
        `when`(authContext.optionalUserId("auth")).thenReturn(me)
        `when`(positionService.positionViewsForUser(leagueId, me)).thenReturn(emptyList())

        val res = controller.memberPositions("auth", leagueId.toString(), me.toString())

        assertThat(res.success).isTrue()
        verify(leagueService, never()).get(leagueId)  // 본인이면 리그 가시성 조회조차 안 함
    }
}
