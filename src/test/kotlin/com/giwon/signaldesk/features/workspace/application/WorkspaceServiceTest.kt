package com.giwon.signaldesk.features.workspace.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkspaceServiceTest {

    private val service = WorkspaceService(PassThroughWorkspaceRepository())

    // ── savePortfolioPosition ────────────────────────────────────────

    @Test
    fun `evaluationAmount = currentPrice * quantity`() {
        val result = service.savePortfolioPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            buyPrice = 70_000, currentPrice = 75_000, quantity = 10,
        )
        assertEquals(750_000L, result.evaluationAmount)
    }

    @Test
    fun `profitAmount = (currentPrice - buyPrice) * quantity`() {
        val result = service.savePortfolioPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            buyPrice = 70_000, currentPrice = 75_000, quantity = 10,
        )
        assertEquals(50_000L, result.profitAmount)
    }

    @Test
    fun `profitRate = profitAmount 나누기 costAmount * 100`() {
        val result = service.savePortfolioPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            buyPrice = 100_000, currentPrice = 110_000, quantity = 5,
        )
        assertEquals(10.0, result.profitRate, 0.001)
    }

    @Test
    fun `손실 포지션 - profitAmount 음수 profitRate 음수`() {
        val result = service.savePortfolioPosition(
            id = "", market = "US", ticker = "AAPL", name = "Apple",
            buyPrice = 200, currentPrice = 180, quantity = 10,
        )
        assertEquals(-200L, result.profitAmount)
        assertTrue(result.profitRate < 0)
    }

    @Test
    fun `buyPrice 0이면 profitRate 0`() {
        val result = service.savePortfolioPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            buyPrice = 0, currentPrice = 70_000, quantity = 1,
        )
        assertEquals(0.0, result.profitRate, 0.001)
    }
}

/** 저장 로직 없이 인자를 그대로 반환하는 테스트용 스텁 */
private class PassThroughWorkspaceRepository : SignalDeskWorkspaceRepository {
    override fun loadWatchlist(userId: UUID?) = emptyList<WorkspaceWatchItem>()
    override fun saveWatchItem(userId: UUID?, item: WorkspaceWatchItem) = item
    override fun deleteWatchItem(userId: UUID?, id: String) = Unit
    override fun loadPortfolioPositions(userId: UUID?) = emptyList<WorkspaceHoldingPosition>()
    override fun savePortfolioPosition(userId: UUID?, position: WorkspaceHoldingPosition) = position
    override fun deletePortfolioPosition(userId: UUID?, id: String) = Unit
    override fun loadAiPicks(userId: UUID?) = emptyList<WorkspaceAiPick>()
    override fun saveAiPick(userId: UUID?, pick: WorkspaceAiPick) = pick
    override fun deleteAiPick(userId: UUID?, id: String) = Unit
    override fun loadAiTrackRecords(userId: UUID?) = emptyList<WorkspaceAiTrackRecord>()
    override fun saveAiTrackRecord(userId: UUID?, record: WorkspaceAiTrackRecord) = record
    override fun deleteAiTrackRecord(userId: UUID?, id: String) = Unit
    override fun loadAllUserAiTrackRecords() = emptyList<WorkspaceAiTrackRecord>()
    override fun updateAiTrackRecordPrice(id: String, latestPrice: Int, realizedReturnRate: Double, success: Boolean) = Unit
}
