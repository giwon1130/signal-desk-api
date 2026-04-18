package com.giwon.signaldesk.features.workspace.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/workspace")
class WorkspaceController(
    private val workspaceStore: SignalDeskWorkspaceRepository,
    private val workspaceService: WorkspaceService,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {

    /** AuthContext bean 이 없으면(JDBC 모드 비활성화) null → userId 도 null (글로벌 데이터). */
    private fun userId(auth: String?) = authContext?.optionalUserId(auth)

    @GetMapping("/watchlist")
    fun getWatchlist(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceWatchItem>> =
        ApiResponse(true, workspaceStore.loadWatchlist(userId(auth)))

    @PostMapping("/watchlist")
    fun saveWatchlistItem(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SaveWatchlistItemRequest,
    ): ApiResponse<WorkspaceWatchItem> =
        ApiResponse(true, workspaceStore.saveWatchItem(userId(auth),
            WorkspaceWatchItem(id = request.id, market = request.market, ticker = request.ticker,
                name = request.name, price = request.price, changeRate = request.changeRate,
                sector = request.sector, stance = request.stance, note = request.note)
        ))

    @DeleteMapping("/watchlist/{id}")
    fun deleteWatchlistItem(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deleteWatchItem(userId(auth), id)
        return ApiResponse(true, true)
    }

    @GetMapping("/portfolio")
    fun getPortfolio(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceHoldingPosition>> =
        ApiResponse(true, workspaceStore.loadPortfolioPositions(userId(auth)))

    @PostMapping("/portfolio")
    fun savePortfolioPosition(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SavePortfolioPositionRequest,
    ): ApiResponse<WorkspaceHoldingPosition> =
        ApiResponse(true, workspaceService.savePortfolioPosition(userId(auth),
            id = request.id, market = request.market, ticker = request.ticker, name = request.name,
            buyPrice = request.buyPrice, currentPrice = request.currentPrice, quantity = request.quantity,
        ))

    @DeleteMapping("/portfolio/{id}")
    fun deletePortfolioPosition(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deletePortfolioPosition(userId(auth), id)
        return ApiResponse(true, true)
    }

    @GetMapping("/paper/positions")
    fun getPaperPositions(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspacePaperPosition>> =
        ApiResponse(true, workspaceStore.loadPaperPositions(userId(auth)))

    @PostMapping("/paper/positions")
    fun savePaperPosition(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SavePaperPositionRequest,
    ): ApiResponse<WorkspacePaperPosition> =
        ApiResponse(true, workspaceService.savePaperPosition(userId(auth),
            id = request.id, market = request.market, ticker = request.ticker, name = request.name,
            averagePrice = request.averagePrice, currentPrice = request.currentPrice, quantity = request.quantity,
        ))

    @DeleteMapping("/paper/positions/{id}")
    fun deletePaperPosition(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deletePaperPosition(userId(auth), id)
        return ApiResponse(true, true)
    }

    @GetMapping("/paper/trades")
    fun getPaperTrades(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspacePaperTrade>> =
        ApiResponse(true, workspaceStore.loadPaperTrades(userId(auth)))

    @PostMapping("/paper/trades")
    fun savePaperTrade(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SavePaperTradeRequest,
    ): ApiResponse<WorkspacePaperTrade> =
        ApiResponse(true, workspaceStore.savePaperTrade(userId(auth),
            WorkspacePaperTrade(id = request.id, tradeDate = request.tradeDate, side = request.side,
                market = request.market, ticker = request.ticker, name = request.name,
                price = request.price, quantity = request.quantity)
        ))

    @DeleteMapping("/paper/trades/{id}")
    fun deletePaperTrade(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deletePaperTrade(userId(auth), id)
        return ApiResponse(true, true)
    }

    @GetMapping("/ai/picks")
    fun getAiPicks(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceAiPick>> =
        ApiResponse(true, workspaceStore.loadAiPicks(userId(auth)))

    @PostMapping("/ai/picks")
    fun saveAiPick(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SaveAiPickRequest,
    ): ApiResponse<WorkspaceAiPick> =
        ApiResponse(true, workspaceStore.saveAiPick(userId(auth),
            WorkspaceAiPick(id = request.id, market = request.market, ticker = request.ticker,
                name = request.name, basis = request.basis, confidence = request.confidence,
                note = request.note, expectedReturnRate = request.expectedReturnRate)
        ))

    @DeleteMapping("/ai/picks/{id}")
    fun deleteAiPick(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deleteAiPick(userId(auth), id)
        return ApiResponse(true, true)
    }

    @GetMapping("/ai/track-records")
    fun getAiTrackRecords(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceAiTrackRecord>> =
        ApiResponse(true, workspaceStore.loadAiTrackRecords(userId(auth)))

    @PostMapping("/ai/track-records")
    fun saveAiTrackRecord(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SaveAiTrackRecordRequest,
    ): ApiResponse<WorkspaceAiTrackRecord> =
        ApiResponse(true, workspaceService.saveAiTrackRecord(userId(auth),
            id = request.id, recommendedDate = request.recommendedDate, market = request.market,
            ticker = request.ticker, name = request.name, entryPrice = request.entryPrice,
            latestPrice = request.latestPrice,
        ))

    @DeleteMapping("/ai/track-records/{id}")
    fun deleteAiTrackRecord(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deleteAiTrackRecord(userId(auth), id)
        return ApiResponse(true, true)
    }
}
