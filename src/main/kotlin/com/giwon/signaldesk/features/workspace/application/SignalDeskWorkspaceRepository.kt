package com.giwon.signaldesk.features.workspace.application

import java.util.UUID

interface SignalDeskWorkspaceRepository {
    fun loadWatchlist(userId: UUID?): List<WorkspaceWatchItem>
    fun saveWatchItem(userId: UUID?, item: WorkspaceWatchItem): WorkspaceWatchItem
    fun deleteWatchItem(userId: UUID?, id: String)

    fun loadPortfolioPositions(userId: UUID?): List<WorkspaceHoldingPosition>
    fun savePortfolioPosition(userId: UUID?, position: WorkspaceHoldingPosition): WorkspaceHoldingPosition
    fun deletePortfolioPosition(userId: UUID?, id: String)

    fun loadPaperPositions(userId: UUID?): List<WorkspacePaperPosition>
    fun savePaperPosition(userId: UUID?, position: WorkspacePaperPosition): WorkspacePaperPosition
    fun deletePaperPosition(userId: UUID?, id: String)

    fun loadPaperTrades(userId: UUID?): List<WorkspacePaperTrade>
    fun savePaperTrade(userId: UUID?, trade: WorkspacePaperTrade): WorkspacePaperTrade
    fun deletePaperTrade(userId: UUID?, id: String)

    fun loadAiPicks(userId: UUID?): List<WorkspaceAiPick>
    fun saveAiPick(userId: UUID?, pick: WorkspaceAiPick): WorkspaceAiPick
    fun deleteAiPick(userId: UUID?, id: String)

    fun loadAiTrackRecords(userId: UUID?): List<WorkspaceAiTrackRecord>
    fun saveAiTrackRecord(userId: UUID?, record: WorkspaceAiTrackRecord): WorkspaceAiTrackRecord
    fun deleteAiTrackRecord(userId: UUID?, id: String)
}
