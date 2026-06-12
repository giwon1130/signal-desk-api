package com.giwon.signaldesk.features.workspace.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
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
    @Autowired(required = false) private val planService: com.giwon.signaldesk.features.plan.PlanService? = null,
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
        val uid = userId(auth)
        if (planService != null && uid != null) {
            val existing = workspaceStore.loadWatchlist(uid)
            // 신규 추가 판정: id 로(또는 dedupe 키 market+ticker 로) 기존 항목이 없으면 새 추가.
            val prior = if (request.id.isNotBlank()) existing.find { it.id == request.id }
                        else existing.find { it.market == request.market && it.ticker == request.ticker }
            if (prior == null) planService.assertCanAdd(uid, com.giwon.signaldesk.features.plan.PlanService.Resource.WATCHLIST, existing.size)
            // 목표가 알림(상한/하한)은 PRO 전용 — FREE 는 새로 켜는 것만 차단(기존 값 유지 = grandfather).
            if (!planService.isPro(uid)) {
                val addingBelow = request.alertBelow != null && prior?.alertBelow == null
                val addingAbove = request.alertAbove != null && prior?.alertAbove == null
                require(!addingBelow && !addingAbove) {
                    "목표가 알림(상한/하한)은 PRO 플랜 전용이에요. PRO 로 업그레이드하면 켤 수 있어요. 💎"
                }
            }
        }
        val saved = workspaceStore.saveWatchItem(uid,
            WorkspaceWatchItem(id = request.id, market = request.market, ticker = request.ticker,
                name = request.name, price = request.price, changeRate = request.changeRate,
                sector = request.sector, stance = request.stance, note = request.note,
                alertBelow = request.alertBelow, alertAbove = request.alertAbove, volumeAlert = request.volumeAlert))
        logger.info("watchlist save user={} market={} ticker={} new={}", userTag(auth), request.market, request.ticker, request.id.isBlank())
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
        val uid = userId(auth)
        // 신규 추가(id 공백)만 상한 적용 — 기존 보유 수정은 카운트 제외(grandfather).
        if (planService != null && uid != null && request.id.isBlank()) {
            val count = workspaceStore.loadPortfolioPositions(uid).size
            planService.assertCanAdd(uid, com.giwon.signaldesk.features.plan.PlanService.Resource.HOLDINGS, count)
        }
        val saved = workspaceService.savePortfolioPosition(uid,
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

    // AI picks/track-records 의 외부 GET 은 /api/v1/market/ai-recommendations (MarketOverviewController)로 통합됨.
}
