package com.giwon.signaldesk.features.workspace.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.plan.PlanService
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import com.giwon.signaldesk.features.workspace.application.WorkspaceHoldingPosition
import com.giwon.signaldesk.features.workspace.application.WorkspaceService
import com.giwon.signaldesk.features.workspace.application.WorkspaceWatchItem
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * PRO 게이팅 회귀 보호 — 관심/보유 상한 + 목표가 알림 PRO 전용.
 * grandfather: 기존 항목 수정은 상한 카운트 제외.
 */
class WorkspaceControllerProGatingTest {

    private val store = mock(SignalDeskWorkspaceRepository::class.java)
    private val service = mock(WorkspaceService::class.java)
    private val authContext = mock(AuthContext::class.java)
    private val planService = mock(PlanService::class.java)
    private val controller = WorkspaceController(store, service, authContext, planService)

    private val auth = "Bearer x"
    private val uid = UUID.randomUUID()

    private fun watch(ticker: String, alertBelow: Int? = null, alertAbove: Int? = null) =
        WorkspaceWatchItem(id = UUID.randomUUID().toString(), market = "KR", ticker = ticker,
            name = ticker, price = 1000, changeRate = 0.0, sector = "", stance = "관찰", note = "",
            alertBelow = alertBelow, alertAbove = alertAbove)

    init {
        `when`(authContext.optionalUserId(auth)).thenReturn(uid)
    }

    @Test
    fun `신규 관심종목은 현재 개수로 상한 검사`() {
        val existing = (1..3).map { watch("00000$it") }
        `when`(store.loadWatchlist(uid)).thenReturn(existing)
        `when`(planService.isPro(uid)).thenReturn(false)

        controller.saveWatchlistItem(auth, SaveWatchlistItemRequest(market = "KR", ticker = "005930", name = "삼성", price = 70000, changeRate = 0.0))

        verify(planService).assertCanAdd(uid, PlanService.Resource.WATCHLIST, 3)
    }

    @Test
    fun `기존 종목 수정은 상한 적용 안 함(grandfather)`() {
        val existing = (1..20).map { watch("ticker$it") } + watch("005930")
        `when`(store.loadWatchlist(uid)).thenReturn(existing)
        `when`(planService.isPro(uid)).thenReturn(false)

        // 이미 담긴 005930 재저장(수정) — 상한이 꽉 차도 통과해야 한다.
        assertThatCode {
            controller.saveWatchlistItem(auth, SaveWatchlistItemRequest(market = "KR", ticker = "005930", name = "삼성", price = 70000, changeRate = 0.0))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `FREE 는 목표가 알림 신규 설정 거부`() {
        `when`(store.loadWatchlist(uid)).thenReturn(emptyList())
        `when`(planService.isPro(uid)).thenReturn(false)

        assertThatThrownBy {
            controller.saveWatchlistItem(auth, SaveWatchlistItemRequest(market = "KR", ticker = "005930", name = "삼성", price = 70000, changeRate = 0.0, alertBelow = 60000))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `신규 보유종목은 상한 검사`() {
        val existing = (1..10).map {
            WorkspaceHoldingPosition(id = UUID.randomUUID().toString(), market = "KR", ticker = "t$it", name = "t$it",
                buyPrice = 100, currentPrice = 100, quantity = 1, profitAmount = 0, evaluationAmount = 100, profitRate = 0.0)
        }
        `when`(store.loadPortfolioPositions(uid)).thenReturn(existing)
        doThrow(IllegalArgumentException("보유 상한")).`when`(planService)
            .assertCanAdd(uid, PlanService.Resource.HOLDINGS, 10)

        assertThatThrownBy {
            controller.savePortfolioPosition(auth, SavePortfolioPositionRequest(market = "KR", ticker = "005930", name = "삼성", buyPrice = 70000, currentPrice = 70000, quantity = 1))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
