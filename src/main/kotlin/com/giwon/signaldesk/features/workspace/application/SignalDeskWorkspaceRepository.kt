package com.giwon.signaldesk.features.workspace.application

interface SignalDeskWorkspaceRepository {
    fun loadWatchlist(): List<WorkspaceWatchItem>
    fun saveWatchItem(item: WorkspaceWatchItem): WorkspaceWatchItem
    fun deleteWatchItem(id: String)

    fun loadPortfolioPositions(): List<WorkspaceHoldingPosition>
    fun savePortfolioPosition(position: WorkspaceHoldingPosition): WorkspaceHoldingPosition
    fun deletePortfolioPosition(id: String)

    fun loadPaperPositions(): List<WorkspacePaperPosition>
    fun savePaperPosition(position: WorkspacePaperPosition): WorkspacePaperPosition
    fun deletePaperPosition(id: String)

    fun loadPaperTrades(): List<WorkspacePaperTrade>
    fun savePaperTrade(trade: WorkspacePaperTrade): WorkspacePaperTrade
    fun deletePaperTrade(id: String)

    fun loadAiPicks(): List<WorkspaceAiPick>
    fun saveAiPick(pick: WorkspaceAiPick): WorkspaceAiPick
    fun deleteAiPick(id: String)

    fun loadAiTrackRecords(): List<WorkspaceAiTrackRecord>
    fun saveAiTrackRecord(record: WorkspaceAiTrackRecord): WorkspaceAiTrackRecord
    fun deleteAiTrackRecord(id: String)
}
