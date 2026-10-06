package com.giwon.signaldesk.features.workspace.application

data class StoreFile(
    val watchlist: List<WorkspaceWatchItem> = emptyList(),
    val portfolioPositions: List<WorkspaceHoldingPosition> = emptyList(),
    val aiPicks: List<WorkspaceAiPick> = emptyList(),
    val aiTrackRecords: List<WorkspaceAiTrackRecord> = emptyList(),
)

data class WorkspaceWatchItem(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val price: Double,
    val changeRate: Double,
    val sector: String,
    val stance: String,
    val note: String,
    val alertBelow: Double? = null,
    val alertAbove: Double? = null,
    val volumeAlert: Boolean = false,
)

data class WorkspaceHoldingPosition(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val buyPrice: Double,
    val currentPrice: Double,
    val quantity: Double,
    val profitAmount: Double,
    val evaluationAmount: Double,
    val profitRate: Double,
    val targetPrice: Double? = null,
    val stopLossPrice: Double? = null,
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
    val entryPrice: Double,
    val latestPrice: Double,
    val realizedReturnRate: Double,
    val success: Boolean,
)
