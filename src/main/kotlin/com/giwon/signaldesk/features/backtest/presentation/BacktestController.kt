package com.giwon.signaldesk.features.backtest.presentation

import com.giwon.signaldesk.features.backtest.application.SeasonalityBacktestService
import com.giwon.signaldesk.features.backtest.application.SeasonalityReport
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시즈널리티 백테스트 — 종목 월별/요일별 역사적 패턴 리포트.
 *   GET /api/v1/backtest/seasonality?market=US&ticker=GOOGL&years=12&costPct=0.10
 * 인증 불필요(공개 통계). 비용 기본값: US 0.10% / KR 0.25%(거래세+슬리피지).
 */
@RestController
@RequestMapping("/api/v1/backtest")
class BacktestController(
    private val seasonalityService: SeasonalityBacktestService,
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
}
