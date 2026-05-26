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
    // sector/stance/note 는 AI 픽 quick-add 등 일부 흐름에서 비어있을 수 있음 — @NotBlank 제거하고
    // 기본값으로 보완. 핵심 식별자(market/ticker/name)와 price만 강제.
    val sector: String = "",
    val stance: String = "관찰",
    val note: String = "관심종목",
    val alertBelow: Int? = null,
    val alertAbove: Int? = null,
    val volumeAlert: Boolean = false,
)

data class SavePortfolioPositionRequest(
    val id: String = "",
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Min(0) val buyPrice: Int,
    @field:Min(0) val currentPrice: Int,
    @field:Min(1) val quantity: Int,
    val targetPrice: Int? = null,
    val stopLossPrice: Int? = null,
)

