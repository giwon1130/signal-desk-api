package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.features.market.application.StockSearchResult
import com.giwon.signaldesk.features.market.application.StockSearchService
import com.giwon.signaldesk.features.market.application.MoverReasonService
import com.giwon.signaldesk.features.market.application.MoverReasonTarget
import com.giwon.signaldesk.features.market.application.MoverReason
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/market/stocks")
class StockSearchController(
    private val stockSearchService: StockSearchService,
    private val moverReasonService: MoverReasonService,
) {
    /** On-demand stock details use a server-resolved company, never a user-supplied cause/name. */
    @GetMapping("/context")
    fun context(@RequestParam market: String, @RequestParam ticker: String): ApiResponse<MoverReason?> {
        if (market !in setOf("KR", "US") || !ticker.matches(if (market == "KR") Regex("[0-9]{6}") else Regex("[A-Z][A-Z0-9.-]{0,9}"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid market or ticker")
        }
        val stock = stockSearchService.search(ticker, market, 20).firstOrNull { it.market == market && it.ticker == ticker }
            ?: return ApiResponse(true, null)
        val target = MoverReasonTarget(stock.market, stock.ticker, stock.name, stock.changeRate)
        val context = moverReasonService.contextsForTickers(listOf(target))[market to ticker] ?: return ApiResponse(true, null)
        return ApiResponse(true, MoverReason(market, ticker, stock.name, if (stock.changeRate >= 0) "UP" else "DOWN", stock.changeRate, context.summary, context))
    }

    @GetMapping("/search")
    fun search(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(required = false) market: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ApiResponse<List<StockSearchResult>> {
        val safeLimit = limit.coerceIn(1, 50)
        return ApiResponse(true, stockSearchService.search(q, market, safeLimit))
    }
}
