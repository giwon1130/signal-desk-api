package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class TradingCalendarController(private val sessions: MarketSessionService) {
    @GetMapping("/api/v1/market/trading-calendar")
    fun upcoming() = ApiResponse(true, sessions.upcomingSessions())
}
