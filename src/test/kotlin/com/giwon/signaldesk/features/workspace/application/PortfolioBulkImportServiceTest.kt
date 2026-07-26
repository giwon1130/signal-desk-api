package com.giwon.signaldesk.features.workspace.application

import com.giwon.signaldesk.features.plan.PlanService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

class PortfolioBulkImportServiceTest {
    private val userId = UUID.randomUUID()

    @Test
    fun `같은 종목은 기존 id와 목표 손절가를 보존해 갱신한다`() {
        val prior = holding(id = "existing", ticker = "005930", target = 90_000, stop = 60_000)
        val repository = RecordingRepository(listOf(prior))
        val service = PortfolioBulkImportService(repository, WorkspaceService(repository))

        val saved = service.save(userId, listOf(
            PortfolioImportPositionDraft("KR", "005930", "삼성전자", 70_000, 75_000, 10),
        ))

        assertThat(saved).hasSize(1)
        assertThat(saved[0].id).isEqualTo("existing")
        assertThat(saved[0].targetPrice).isEqualTo(90_000)
        assertThat(saved[0].stopLossPrice).isEqualTo(60_000)
    }

    @Test
    fun `신규 종목마다 증가한 현재 개수로 플랜 상한을 검사한다`() {
        val repository = RecordingRepository(listOf(holding(id = "one", ticker = "005930")))
        val plan = mock(PlanService::class.java)
        val service = PortfolioBulkImportService(repository, WorkspaceService(repository), plan)

        service.save(userId, listOf(
            PortfolioImportPositionDraft("KR", "000660", "SK하이닉스", 200_000, 190_000, 2),
            PortfolioImportPositionDraft("US", "NVDA", "NVIDIA", 150, 145, 3),
        ))

        verify(plan).assertCanAdd(userId, PlanService.Resource.HOLDINGS, 1)
        verify(plan).assertCanAdd(userId, PlanService.Resource.HOLDINGS, 2)
    }

    private fun holding(id: String, ticker: String, target: Int? = null, stop: Int? = null) =
        WorkspaceHoldingPosition(
            id = id, market = "KR", ticker = ticker, name = ticker,
            buyPrice = 100, currentPrice = 100, quantity = 1,
            profitAmount = 0, evaluationAmount = 100, profitRate = 0.0,
            targetPrice = target, stopLossPrice = stop,
        )
}

private class RecordingRepository(initial: List<WorkspaceHoldingPosition>) : SignalDeskWorkspaceRepository {
    private val positions = initial.toMutableList()
    override fun loadWatchlist(userId: UUID?) = emptyList<WorkspaceWatchItem>()
    override fun saveWatchItem(userId: UUID?, item: WorkspaceWatchItem) = item
    override fun deleteWatchItem(userId: UUID?, id: String) = Unit
    override fun loadPortfolioPositions(userId: UUID?) = positions.toList()
    override fun savePortfolioPosition(userId: UUID?, position: WorkspaceHoldingPosition): WorkspaceHoldingPosition {
        positions.removeAll { it.id == position.id }
        positions += position
        return position
    }
    override fun deletePortfolioPosition(userId: UUID?, id: String) = Unit
    override fun loadAiPicks(userId: UUID?) = emptyList<WorkspaceAiPick>()
    override fun deleteAiPick(userId: UUID?, id: String) = Unit
    override fun loadAiTrackRecords(userId: UUID?) = emptyList<WorkspaceAiTrackRecord>()
}
