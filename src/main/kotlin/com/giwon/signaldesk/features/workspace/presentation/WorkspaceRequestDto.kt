package com.giwon.signaldesk.features.workspace.presentation

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class SaveWatchlistItemRequest(
    val id: String = "",
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val price: Double,
    val changeRate: Double,
    // sector/stance/note 는 AI 픽 quick-add 등 일부 흐름에서 비어있을 수 있음 — @NotBlank 제거하고
    // 기본값으로 보완. 핵심 식별자(market/ticker/name)와 price만 강제.
    val sector: String = "",
    val stance: String = "관찰",
    val note: String = "관심종목",
    val alertBelow: Double? = null,
    val alertAbove: Double? = null,
    val volumeAlert: Boolean = false,
)

data class SavePortfolioPositionRequest(
    val id: String = "",
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val buyPrice: Double,
    @field:Min(0) val currentPrice: Double,
    @field:jakarta.validation.constraints.Positive val quantity: Double,
    val targetPrice: Double? = null,
    val stopLossPrice: Double? = null,
)
