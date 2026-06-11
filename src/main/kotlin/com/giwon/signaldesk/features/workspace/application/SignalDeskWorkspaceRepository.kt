package com.giwon.signaldesk.features.workspace.application

import java.util.UUID

interface SignalDeskWorkspaceRepository {
    fun loadWatchlist(userId: UUID?): List<WorkspaceWatchItem>
    fun saveWatchItem(userId: UUID?, item: WorkspaceWatchItem): WorkspaceWatchItem
    fun deleteWatchItem(userId: UUID?, id: String)

    fun loadPortfolioPositions(userId: UUID?): List<WorkspaceHoldingPosition>
    fun savePortfolioPosition(userId: UUID?, position: WorkspaceHoldingPosition): WorkspaceHoldingPosition
    fun deletePortfolioPosition(userId: UUID?, id: String)

    fun loadAiPicks(userId: UUID?): List<WorkspaceAiPick>
    fun deleteAiPick(userId: UUID?, id: String)

    fun loadAiTrackRecords(userId: UUID?): List<WorkspaceAiTrackRecord>
}
