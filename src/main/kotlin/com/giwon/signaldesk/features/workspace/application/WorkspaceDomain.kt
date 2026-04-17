package com.giwon.signaldesk.features.workspace.application

data class StoreFile(
    val watchlist: List<WorkspaceWatchItem> = emptyList(),
    val portfolioPositions: List<WorkspaceHoldingPosition> = emptyList(),
    val paperPositions: List<WorkspacePaperPosition> = emptyList(),
    val paperTrades: List<WorkspacePaperTrade> = emptyList(),
    val aiPicks: List<WorkspaceAiPick> = emptyList(),
    val aiTrackRecords: List<WorkspaceAiTrackRecord> = emptyList(),
)

data class WorkspaceWatchItem(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val price: Int,
    val changeRate: Double,
    val sector: String,
    val stance: String,
    val note: String,
)

data class WorkspaceHoldingPosition(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val buyPrice: Int,
    val currentPrice: Int,
    val quantity: Int,
    val profitAmount: Long,
    val evaluationAmount: Long,
    val profitRate: Double,
)

data class WorkspacePaperPosition(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val averagePrice: Int,
    val currentPrice: Int,
    val quantity: Int,
    val returnRate: Double,
)

data class WorkspacePaperTrade(
    val id: String = "",
    val tradeDate: String,
    val side: String,
    val market: String,
    val ticker: String,
    val name: String,
    val price: Int,
    val quantity: Int,
)

data class WorkspaceAiPick(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val basis: String,
    val confidence: Int,
    val note: String,
    val expectedReturnRate: Double,
)

data class WorkspaceAiTrackRecord(
    val id: String = "",
    val recommendedDate: String,
    val market: String,
    val ticker: String,
    val name: String,
    val entryPrice: Int,
    val latestPrice: Int,
    val realizedReturnRate: Double,
    val success: Boolean,
)
