package com.giwon.signaldesk.features.workspace.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiPick
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiTrackRecord
import com.giwon.signaldesk.features.workspace.application.WorkspaceHoldingPosition
import com.giwon.signaldesk.features.workspace.application.WorkspaceService
import com.giwon.signaldesk.features.workspace.application.WorkspaceWatchItem
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
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

    private val logger = LoggerFactory.getLogger(WorkspaceController::class.java)

    /** AuthContext bean 이 없으면(JDBC 모드 비활성화) null → userId 도 null (글로벌 데이터). */
    private fun userId(auth: String?) = authContext?.optionalUserId(auth)

    /** 로그용 user 식별자 — 운영에서 user UUID 로 query 가능하게. 없으면 anon. */
    private fun userTag(auth: String?): String = userId(auth)?.toString()?.take(8) ?: "anon"

    @GetMapping("/watchlist")
    fun getWatchlist(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceWatchItem>> =
        ApiResponse(true, workspaceStore.loadWatchlist(userId(auth)))

    @PostMapping("/watchlist")
    fun saveWatchlistItem(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SaveWatchlistItemRequest,
    ): ApiResponse<WorkspaceWatchItem> {
        val saved = workspaceStore.saveWatchItem(userId(auth),
            WorkspaceWatchItem(id = request.id, market = request.market, ticker = request.ticker,
                name = request.name, price = request.price, changeRate = request.changeRate,
                sector = request.sector, stance = request.stance, note = request.note,
                alertBelow = request.alertBelow, alertAbove = request.alertAbove, volumeAlert = request.volumeAlert))
        logger.info("watchlist save user={} market={} ticker={} new={}", userTag(auth), request.market, request.ticker, request.id == null)
        return ApiResponse(true, saved)
    }

    @DeleteMapping("/watchlist/{id}")
    fun deleteWatchlistItem(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deleteWatchItem(userId(auth), id)
        logger.info("watchlist delete user={} id={}", userTag(auth), id)
        return ApiResponse(true, true)
    }

    @GetMapping("/portfolio")
    fun getPortfolio(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceHoldingPosition>> =
        ApiResponse(true, workspaceStore.loadPortfolioPositions(userId(auth)))

    @PostMapping("/portfolio")
    fun savePortfolioPosition(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SavePortfolioPositionRequest,
    ): ApiResponse<WorkspaceHoldingPosition> {
        val saved = workspaceService.savePortfolioPosition(userId(auth),
            id = request.id, market = request.market, ticker = request.ticker, name = request.name,
            buyPrice = request.buyPrice, currentPrice = request.currentPrice, quantity = request.quantity,
            targetPrice = request.targetPrice, stopLossPrice = request.stopLossPrice)
        logger.info("portfolio save user={} market={} ticker={} qty={} new={}",
            userTag(auth), request.market, request.ticker, request.quantity, request.id == null)
        return ApiResponse(true, saved)
    }

    @DeleteMapping("/portfolio/{id}")
    fun deletePortfolioPosition(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        workspaceStore.deletePortfolioPosition(userId(auth), id)
        logger.info("portfolio delete user={} id={}", userTag(auth), id)
        return ApiResponse(true, true)
    }

    // AI picks/track-records: 시스템이 내부에서 생성하고 사용자는 읽기만 한다.
    // 외부 노출하는 GET endpoint는 /api/v1/market/ai-recommendations (MarketOverviewController)로 통합 — 여기는 보존만.

    @GetMapping("/ai/picks")
    fun getAiPicks(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceAiPick>> =
        ApiResponse(true, workspaceStore.loadAiPicks(userId(auth)))

    @GetMapping("/ai/track-records")
    fun getAiTrackRecords(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<WorkspaceAiTrackRecord>> =
        ApiResponse(true, workspaceStore.loadAiTrackRecords(userId(auth)))
}
