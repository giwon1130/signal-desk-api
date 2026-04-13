package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.features.market.application.StockSearchResult
import com.giwon.signaldesk.features.market.application.StockSearchService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/market/stocks")
class StockSearchController(
    private val stockSearchService: StockSearchService,
) {
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
