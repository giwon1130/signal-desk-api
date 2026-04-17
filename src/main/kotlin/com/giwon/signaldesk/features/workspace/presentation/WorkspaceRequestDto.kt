package com.giwon.signaldesk.features.workspace.presentation

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class SaveWatchlistItemRequest(
    val id: String = "",
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val price: Int,
    val changeRate: Double,
    @field:NotBlank val sector: String,
    @field:NotBlank val stance: String,
    @field:NotBlank val note: String,
)

data class SavePortfolioPositionRequest(
    val id: String = "",
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val buyPrice: Int,
    @field:Min(0) val currentPrice: Int,
    @field:Min(1) val quantity: Int,
)

data class SavePaperPositionRequest(
    val id: String = "",
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val averagePrice: Int,
    @field:Min(0) val currentPrice: Int,
    @field:Min(1) val quantity: Int,
)

data class SavePaperTradeRequest(
    val id: String = "",
    @field:NotBlank val tradeDate: String,
    @field:NotBlank val side: String,
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val price: Int,
    @field:Min(1) val quantity: Int,
)

data class SaveAiPickRequest(
    val id: String = "",
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:NotBlank val basis: String,
    @field:Min(0) val confidence: Int,
    @field:NotBlank val note: String,
    val expectedReturnRate: Double,
)

data class SaveAiTrackRecordRequest(
    val id: String = "",
    @field:NotBlank val recommendedDate: String,
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val entryPrice: Int,
    @field:Min(0) val latestPrice: Int,
)
