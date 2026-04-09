package com.giwon.signaldesk.features.workspace.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceStore
import com.giwon.signaldesk.features.workspace.application.WorkspaceHoldingPosition
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiPick
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiTrackRecord
import com.giwon.signaldesk.features.workspace.application.WorkspacePaperPosition
import com.giwon.signaldesk.features.workspace.application.WorkspacePaperTrade
import com.giwon.signaldesk.features.workspace.application.WorkspaceWatchItem
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/workspace")
class WorkspaceController(
    private val workspaceStore: SignalDeskWorkspaceStore,
) {

    @GetMapping("/watchlist")
    fun getWatchlist(): ApiResponse<List<WorkspaceWatchItem>> {
        return ApiResponse(true, workspaceStore.loadWatchlist())
    }

    @PostMapping("/watchlist")
    fun saveWatchlistItem(@Valid @RequestBody request: SaveWatchlistItemRequest): ApiResponse<WorkspaceWatchItem> {
        return ApiResponse(
            true,
            workspaceStore.saveWatchItem(
                WorkspaceWatchItem(
                    id = request.id,
                    market = request.market,
                    ticker = request.ticker,
                    name = request.name,
                    price = request.price,
                    changeRate = request.changeRate,
                    sector = request.sector,
                    stance = request.stance,
                    note = request.note,
                )
            )
        )
    }

    @DeleteMapping("/watchlist/{id}")
    fun deleteWatchlistItem(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deleteWatchItem(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/portfolio")
    fun getPortfolio(): ApiResponse<List<WorkspaceHoldingPosition>> {
        return ApiResponse(true, workspaceStore.loadPortfolioPositions())
    }

    @PostMapping("/portfolio")
    fun savePortfolioPosition(@Valid @RequestBody request: SavePortfolioPositionRequest): ApiResponse<WorkspaceHoldingPosition> {
        val evaluationAmount = request.currentPrice * request.quantity
        val costAmount = request.buyPrice * request.quantity
        val profitAmount = evaluationAmount - costAmount

        return ApiResponse(
            true,
            workspaceStore.savePortfolioPosition(
                WorkspaceHoldingPosition(
                    id = request.id,
                    market = request.market,
                    ticker = request.ticker,
                    name = request.name,
                    buyPrice = request.buyPrice,
                    currentPrice = request.currentPrice,
                    quantity = request.quantity,
                    profitAmount = profitAmount,
                    evaluationAmount = evaluationAmount,
                    profitRate = if (costAmount == 0) 0.0 else (profitAmount.toDouble() / costAmount) * 100,
                )
            )
        )
    }

    @DeleteMapping("/portfolio/{id}")
    fun deletePortfolioPosition(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deletePortfolioPosition(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/paper/positions")
    fun getPaperPositions(): ApiResponse<List<WorkspacePaperPosition>> {
        return ApiResponse(true, workspaceStore.loadPaperPositions())
    }

    @PostMapping("/paper/positions")
    fun savePaperPosition(@Valid @RequestBody request: SavePaperPositionRequest): ApiResponse<WorkspacePaperPosition> {
        val returnRate = if (request.averagePrice == 0) 0.0 else ((request.currentPrice - request.averagePrice).toDouble() / request.averagePrice) * 100
        return ApiResponse(
            true,
            workspaceStore.savePaperPosition(
                WorkspacePaperPosition(
                    id = request.id,
                    market = request.market,
                    ticker = request.ticker,
                    name = request.name,
                    averagePrice = request.averagePrice,
                    currentPrice = request.currentPrice,
                    quantity = request.quantity,
                    returnRate = returnRate,
                )
            )
        )
    }

    @DeleteMapping("/paper/positions/{id}")
    fun deletePaperPosition(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deletePaperPosition(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/paper/trades")
    fun getPaperTrades(): ApiResponse<List<WorkspacePaperTrade>> {
        return ApiResponse(true, workspaceStore.loadPaperTrades())
    }

    @PostMapping("/paper/trades")
    fun savePaperTrade(@Valid @RequestBody request: SavePaperTradeRequest): ApiResponse<WorkspacePaperTrade> {
        return ApiResponse(
            true,
            workspaceStore.savePaperTrade(
                WorkspacePaperTrade(
                    id = request.id,
                    tradeDate = request.tradeDate,
                    side = request.side,
                    market = request.market,
                    ticker = request.ticker,
                    name = request.name,
                    price = request.price,
                    quantity = request.quantity,
                )
            )
        )
    }

    @DeleteMapping("/paper/trades/{id}")
    fun deletePaperTrade(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deletePaperTrade(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/ai/picks")
    fun getAiPicks(): ApiResponse<List<WorkspaceAiPick>> {
        return ApiResponse(true, workspaceStore.loadAiPicks())
    }

    @PostMapping("/ai/picks")
    fun saveAiPick(@Valid @RequestBody request: SaveAiPickRequest): ApiResponse<WorkspaceAiPick> {
        return ApiResponse(
            true,
            workspaceStore.saveAiPick(
                WorkspaceAiPick(
                    id = request.id,
                    market = request.market,
                    ticker = request.ticker,
                    name = request.name,
                    basis = request.basis,
                    confidence = request.confidence,
                    note = request.note,
                    expectedReturnRate = request.expectedReturnRate,
                )
            )
        )
    }

    @DeleteMapping("/ai/picks/{id}")
    fun deleteAiPick(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deleteAiPick(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/ai/track-records")
    fun getAiTrackRecords(): ApiResponse<List<WorkspaceAiTrackRecord>> {
        return ApiResponse(true, workspaceStore.loadAiTrackRecords())
    }

    @PostMapping("/ai/track-records")
    fun saveAiTrackRecord(@Valid @RequestBody request: SaveAiTrackRecordRequest): ApiResponse<WorkspaceAiTrackRecord> {
        val realizedReturnRate = if (request.entryPrice == 0) 0.0 else ((request.latestPrice - request.entryPrice).toDouble() / request.entryPrice) * 100
        return ApiResponse(
            true,
            workspaceStore.saveAiTrackRecord(
                WorkspaceAiTrackRecord(
                    id = request.id,
                    recommendedDate = request.recommendedDate,
                    market = request.market,
                    ticker = request.ticker,
                    name = request.name,
                    entryPrice = request.entryPrice,
                    latestPrice = request.latestPrice,
                    realizedReturnRate = realizedReturnRate,
                    success = realizedReturnRate >= 0,
                )
            )
        )
    }

    @DeleteMapping("/ai/track-records/{id}")
    fun deleteAiTrackRecord(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deleteAiTrackRecord(id)
        return ApiResponse(true, true)
    }
}

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
