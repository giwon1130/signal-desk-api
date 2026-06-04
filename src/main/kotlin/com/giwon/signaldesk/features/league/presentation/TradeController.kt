package com.giwon.signaldesk.features.league.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.league.application.LeagueService
import com.giwon.signaldesk.features.league.application.PositionService
import com.giwon.signaldesk.features.league.application.TradeService
import com.giwon.signaldesk.features.league.domain.LeagueVisibility
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/league/{leagueId}/trades")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class TradeController(
    private val tradeService: TradeService,
    private val positionService: PositionService,
    private val leagueService: LeagueService,
    private val authContext: AuthContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun requireUserId(auth: String?): UUID =
        authContext.optionalUserId(auth) ?: error("auth required")

    /** 매수/매도 — 백엔드가 시세 fetch + lock + 검증 + INSERT. */
    @PostMapping
    fun placeTrade(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leagueId: String,
        @Valid @RequestBody req: PlaceTradeRequest,
    ): ApiResponse<TradeResponse> {
        val userId = requireUserId(auth)
        val trade = tradeService.placeTrade(
            leagueId = UUID.fromString(leagueId),
            userId = userId,
            market = req.market,
            ticker = req.ticker,
            side = req.side,
            quantity = req.quantity,
            name = req.name,
        )
        return ApiResponse(true, TradeResponse.from(trade))
    }

    /**
     * 거래 피드.
     * - visibility=OPEN 이면 전체 (공개 피드)
     * - visibility=CLOSED 이면 본인 것만
     */
    @GetMapping
    fun list(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leagueId: String,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ApiResponse<List<TradeResponse>> {
        val userId = requireUserId(auth)
        val league = leagueService.get(UUID.fromString(leagueId)) ?: error("league not found")
        val list = if (league.visibility == LeagueVisibility.OPEN) {
            tradeService.listLeagueTrades(league.id, limit)
        } else {
            tradeService.listMyTrades(league.id, userId)
        }
        return ApiResponse(true, list.map(TradeResponse::from))
    }

    /** 내 거래 (visibility 무관, 항상 본인). */
    @GetMapping("/me")
    fun listMine(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leagueId: String,
    ): ApiResponse<List<TradeResponse>> {
        val userId = requireUserId(auth)
        val list = tradeService.listMyTrades(UUID.fromString(leagueId), userId)
        return ApiResponse(true, list.map(TradeResponse::from))
    }

    /** 내 현재 보유 (positions — derived). */
    @GetMapping("/positions")
    fun positions(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leagueId: String,
    ): ApiResponse<List<PositionResponse>> {
        val userId = requireUserId(auth)
        val list = positionService.positionViewsForUser(UUID.fromString(leagueId), userId)
        return ApiResponse(true, list.map(PositionResponse::from))
    }

    /**
     * 특정 참가자의 현재 보유 (동료 포트폴리오 드릴다운).
     * 공개(OPEN) 리그에서만 타인 조회를 허용한다 — 비공개 리그는 거래 피드와 동일하게 본인만.
     */
    @GetMapping("/positions/{targetUserId}")
    fun memberPositions(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leagueId: String,
        @PathVariable targetUserId: String,
    ): ApiResponse<List<PositionResponse>> {
        val userId = requireUserId(auth)
        val lid = UUID.fromString(leagueId)
        val target = UUID.fromString(targetUserId)
        if (target != userId) {
            val league = leagueService.get(lid) ?: error("league not found")
            require(league.visibility == LeagueVisibility.OPEN) {
                "비공개 리그에서는 다른 참가자의 보유 종목을 볼 수 없습니다"
            }
        }
        val list = positionService.positionViewsForUser(lid, target)
        return ApiResponse(true, list.map(PositionResponse::from))
    }
}
