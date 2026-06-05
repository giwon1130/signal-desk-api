package com.giwon.signaldesk.features.backtest.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.backtest.application.SeasonalityBacktestService
import com.giwon.signaldesk.features.backtest.application.SeasonalityReport
import com.giwon.signaldesk.features.backtest.application.SeasonalityRule
import com.giwon.signaldesk.features.backtest.application.SeasonalityRuleService
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시즈널리티 백테스트 + 알고리즘 포트폴리오(시즌 규칙 저장/알림).
 *   GET    /api/v1/backtest/seasonality?market=US&ticker=GOOGL  — 리포트(공개)
 *   POST   /api/v1/backtest/rules                                — 규칙 저장(인증)
 *   GET    /api/v1/backtest/rules                                — 내 규칙 목록(인증)
 *   DELETE /api/v1/backtest/rules/{id}                           — 규칙 삭제(인증)
 */
@RestController
@RequestMapping("/api/v1/backtest")
class BacktestController(
    private val seasonalityService: SeasonalityBacktestService,
    @Autowired(required = false) private val ruleService: SeasonalityRuleService? = null,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    @GetMapping("/seasonality")
    fun seasonality(
        @RequestParam market: String,
        @RequestParam ticker: String,
        @RequestParam(required = false, defaultValue = "") name: String,
        @RequestParam(required = false, defaultValue = "12") years: Int,
        @RequestParam(required = false) costPct: Double?,
    ): ApiResponse<SeasonalityReport?> {
        val cost = costPct ?: if (market.equals("KR", ignoreCase = true)) 0.25 else 0.10
        val report = seasonalityService.report(market, ticker, name, years, cost)
        return ApiResponse(report != null, report)
    }

    @PostMapping("/rules")
    fun saveRule(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestBody req: SaveRuleRequest,
    ): ApiResponse<SeasonalityRule?> {
        val repo = ruleService ?: return ApiResponse(false, null)
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, null)
        val saved = repo.save(userId, req.market, req.ticker, req.name, req.kind, req.month, req.meanPct, req.winRatePct, req.sampleYears)
        return ApiResponse(saved != null, saved)
    }

    @GetMapping("/rules")
    fun listRules(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<List<SeasonalityRule>> {
        val repo = ruleService ?: return ApiResponse(false, emptyList())
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, emptyList())
        return ApiResponse(true, repo.list(userId))
    }

    @DeleteMapping("/rules/{id}")
    fun deleteRule(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        val repo = ruleService ?: return ApiResponse(false, false)
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, false)
        val uuid = runCatching { java.util.UUID.fromString(id) }.getOrNull() ?: return ApiResponse(false, false)
        return ApiResponse(true, repo.delete(userId, uuid))
    }
}

data class SaveRuleRequest(
    val market: String,
    val ticker: String,
    val name: String = "",
    val kind: String,
    val month: Int,
    val meanPct: Double? = null,
    val winRatePct: Double? = null,
    val sampleYears: Int? = null,
)
