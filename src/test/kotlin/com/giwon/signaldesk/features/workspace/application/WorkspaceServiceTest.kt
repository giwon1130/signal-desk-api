package com.giwon.signaldesk.features.workspace.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceServiceTest {

    private val service = WorkspaceService(PassThroughWorkspaceRepository())


    // ── savePortfolioPosition ────────────────────────────────────────

    @Test
    fun `evaluationAmount = currentPrice * quantity`() {
        val result = service.savePortfolioPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            buyPrice = 70_000, currentPrice = 75_000, quantity = 10,
        )
        assertEquals(750_000, result.evaluationAmount)
    }

    @Test
    fun `profitAmount = (currentPrice - buyPrice) * quantity`() {
        val result = service.savePortfolioPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            buyPrice = 70_000, currentPrice = 75_000, quantity = 10,
        )
        assertEquals(50_000, result.profitAmount)
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
        assertEquals(-200, result.profitAmount)
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

    // ── savePaperPosition ────────────────────────────────────────────

    @Test
    fun `returnRate = (currentPrice - averagePrice) 나누기 averagePrice * 100`() {
        val result = service.savePaperPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            averagePrice = 100_000, currentPrice = 120_000, quantity = 5,
        )
        assertEquals(20.0, result.returnRate, 0.001)
    }

    @Test
    fun `모의 매매 손실 - returnRate 음수`() {
        val result = service.savePaperPosition(
            id = "", market = "US", ticker = "TSLA", name = "Tesla",
            averagePrice = 300, currentPrice = 270, quantity = 3,
        )
        assertTrue(result.returnRate < 0)
    }

    @Test
    fun `averagePrice 0이면 returnRate 0`() {
        val result = service.savePaperPosition(
            id = "", market = "KR", ticker = "005930", name = "삼성전자",
            averagePrice = 0, currentPrice = 70_000, quantity = 1,
        )
        assertEquals(0.0, result.returnRate, 0.001)
    }

    @Test
    fun `모의 매매 수량 저장`() {
        val result = service.savePaperPosition(
            id = "", market = "KR", ticker = "035720", name = "카카오",
            averagePrice = 50_000, currentPrice = 55_000, quantity = 7,
        )
        assertEquals(7, result.quantity)
    }

    // ── saveAiTrackRecord ────────────────────────────────────────────

    @Test
    fun `realizedReturnRate = (latestPrice - entryPrice) 나누기 entryPrice * 100`() {
        val result = service.saveAiTrackRecord(
            id = "", recommendedDate = "2025-04-01",
            market = "KR", ticker = "005930", name = "삼성전자",
            entryPrice = 70_000, latestPrice = 77_000,
        )
        assertEquals(10.0, result.realizedReturnRate, 0.001)
    }

    @Test
    fun `수익 트랙레코드 - success true`() {
        val result = service.saveAiTrackRecord(
            id = "", recommendedDate = "2025-04-01",
            market = "KR", ticker = "005930", name = "삼성전자",
            entryPrice = 70_000, latestPrice = 75_000,
        )
        assertTrue(result.success)
    }

    @Test
    fun `손실 트랙레코드 - success false`() {
        val result = service.saveAiTrackRecord(
            id = "", recommendedDate = "2025-04-01",
            market = "US", ticker = "AAPL", name = "Apple",
            entryPrice = 200, latestPrice = 190,
        )
        assertFalse(result.success)
    }

    @Test
    fun `동일 가격 - realizedReturnRate 0 success true`() {
        val result = service.saveAiTrackRecord(
            id = "", recommendedDate = "2025-04-01",
            market = "KR", ticker = "035720", name = "카카오",
            entryPrice = 50_000, latestPrice = 50_000,
        )
        assertEquals(0.0, result.realizedReturnRate, 0.001)
        assertTrue(result.success)
    }

    @Test
    fun `entryPrice 0이면 realizedReturnRate 0`() {
        val result = service.saveAiTrackRecord(
            id = "", recommendedDate = "2025-04-01",
            market = "KR", ticker = "005930", name = "삼성전자",
            entryPrice = 0, latestPrice = 70_000,
        )
        assertEquals(0.0, result.realizedReturnRate, 0.001)
    }
}

/** 저장 로직 없이 인자를 그대로 반환하는 테스트용 스텁 */
private class PassThroughWorkspaceRepository : SignalDeskWorkspaceRepository {
    override fun loadWatchlist() = emptyList<WorkspaceWatchItem>()
    override fun saveWatchItem(item: WorkspaceWatchItem) = item
    override fun deleteWatchItem(id: String) = Unit
    override fun loadPortfolioPositions() = emptyList<WorkspaceHoldingPosition>()
    override fun savePortfolioPosition(position: WorkspaceHoldingPosition) = position
    override fun deletePortfolioPosition(id: String) = Unit
    override fun loadPaperPositions() = emptyList<WorkspacePaperPosition>()
    override fun savePaperPosition(position: WorkspacePaperPosition) = position
    override fun deletePaperPosition(id: String) = Unit
    override fun loadPaperTrades() = emptyList<WorkspacePaperTrade>()
    override fun savePaperTrade(trade: WorkspacePaperTrade) = trade
    override fun deletePaperTrade(id: String) = Unit
    override fun loadAiPicks() = emptyList<WorkspaceAiPick>()
    override fun saveAiPick(pick: WorkspaceAiPick) = pick
    override fun deleteAiPick(id: String) = Unit
    override fun loadAiTrackRecords() = emptyList<WorkspaceAiTrackRecord>()
    override fun saveAiTrackRecord(record: WorkspaceAiTrackRecord) = record
    override fun deleteAiTrackRecord(id: String) = Unit
}
