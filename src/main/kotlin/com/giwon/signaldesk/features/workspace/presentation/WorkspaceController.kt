package com.giwon.signaldesk.features.workspace.presentation

import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiPick
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiTrackRecord
import com.giwon.signaldesk.features.workspace.application.WorkspaceHoldingPosition
import com.giwon.signaldesk.features.workspace.application.WorkspacePaperPosition
import com.giwon.signaldesk.features.workspace.application.WorkspacePaperTrade
import com.giwon.signaldesk.features.workspace.application.WorkspaceService
import com.giwon.signaldesk.features.workspace.application.WorkspaceWatchItem
import jakarta.validation.Valid
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
    private val workspaceStore: SignalDeskWorkspaceRepository,
    private val workspaceService: WorkspaceService,
) {

    @GetMapping("/watchlist")
    fun getWatchlist(): ApiResponse<List<WorkspaceWatchItem>> =
        ApiResponse(true, workspaceStore.loadWatchlist())

    @PostMapping("/watchlist")
    fun saveWatchlistItem(@Valid @RequestBody request: SaveWatchlistItemRequest): ApiResponse<WorkspaceWatchItem> =
        ApiResponse(true, workspaceStore.saveWatchItem(
            WorkspaceWatchItem(id = request.id, market = request.market, ticker = request.ticker,
                name = request.name, price = request.price, changeRate = request.changeRate,
                sector = request.sector, stance = request.stance, note = request.note)
        ))

    @DeleteMapping("/watchlist/{id}")
    fun deleteWatchlistItem(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deleteWatchItem(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/portfolio")
    fun getPortfolio(): ApiResponse<List<WorkspaceHoldingPosition>> =
        ApiResponse(true, workspaceStore.loadPortfolioPositions())

    @PostMapping("/portfolio")
    fun savePortfolioPosition(@Valid @RequestBody request: SavePortfolioPositionRequest): ApiResponse<WorkspaceHoldingPosition> =
        ApiResponse(true, workspaceService.savePortfolioPosition(
            id = request.id, market = request.market, ticker = request.ticker, name = request.name,
            buyPrice = request.buyPrice, currentPrice = request.currentPrice, quantity = request.quantity,
        ))

    @DeleteMapping("/portfolio/{id}")
    fun deletePortfolioPosition(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deletePortfolioPosition(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/paper/positions")
    fun getPaperPositions(): ApiResponse<List<WorkspacePaperPosition>> =
        ApiResponse(true, workspaceStore.loadPaperPositions())

    @PostMapping("/paper/positions")
    fun savePaperPosition(@Valid @RequestBody request: SavePaperPositionRequest): ApiResponse<WorkspacePaperPosition> =
        ApiResponse(true, workspaceService.savePaperPosition(
            id = request.id, market = request.market, ticker = request.ticker, name = request.name,
            averagePrice = request.averagePrice, currentPrice = request.currentPrice, quantity = request.quantity,
        ))

    @DeleteMapping("/paper/positions/{id}")
    fun deletePaperPosition(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deletePaperPosition(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/paper/trades")
    fun getPaperTrades(): ApiResponse<List<WorkspacePaperTrade>> =
        ApiResponse(true, workspaceStore.loadPaperTrades())

    @PostMapping("/paper/trades")
    fun savePaperTrade(@Valid @RequestBody request: SavePaperTradeRequest): ApiResponse<WorkspacePaperTrade> =
        ApiResponse(true, workspaceStore.savePaperTrade(
            WorkspacePaperTrade(id = request.id, tradeDate = request.tradeDate, side = request.side,
                market = request.market, ticker = request.ticker, name = request.name,
                price = request.price, quantity = request.quantity)
        ))

    @DeleteMapping("/paper/trades/{id}")
    fun deletePaperTrade(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deletePaperTrade(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/ai/picks")
    fun getAiPicks(): ApiResponse<List<WorkspaceAiPick>> =
        ApiResponse(true, workspaceStore.loadAiPicks())

    @PostMapping("/ai/picks")
    fun saveAiPick(@Valid @RequestBody request: SaveAiPickRequest): ApiResponse<WorkspaceAiPick> =
        ApiResponse(true, workspaceStore.saveAiPick(
            WorkspaceAiPick(id = request.id, market = request.market, ticker = request.ticker,
                name = request.name, basis = request.basis, confidence = request.confidence,
                note = request.note, expectedReturnRate = request.expectedReturnRate)
        ))

    @DeleteMapping("/ai/picks/{id}")
    fun deleteAiPick(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deleteAiPick(id)
        return ApiResponse(true, true)
    }

    @GetMapping("/ai/track-records")
    fun getAiTrackRecords(): ApiResponse<List<WorkspaceAiTrackRecord>> =
        ApiResponse(true, workspaceStore.loadAiTrackRecords())

    @PostMapping("/ai/track-records")
    fun saveAiTrackRecord(@Valid @RequestBody request: SaveAiTrackRecordRequest): ApiResponse<WorkspaceAiTrackRecord> =
        ApiResponse(true, workspaceService.saveAiTrackRecord(
            id = request.id, recommendedDate = request.recommendedDate, market = request.market,
            ticker = request.ticker, name = request.name, entryPrice = request.entryPrice,
            latestPrice = request.latestPrice,
        ))

    @DeleteMapping("/ai/track-records/{id}")
    fun deleteAiTrackRecord(@PathVariable id: String): ApiResponse<Boolean> {
        workspaceStore.deleteAiTrackRecord(id)
        return ApiResponse(true, true)
    }
}
