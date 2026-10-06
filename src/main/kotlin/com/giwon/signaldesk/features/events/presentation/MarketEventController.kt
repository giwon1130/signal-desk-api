package com.giwon.signaldesk.features.events.presentation

import com.giwon.signaldesk.features.events.application.MarketEvent
import com.giwon.signaldesk.features.events.application.MarketEventService
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/events")
class MarketEventController(private val service: MarketEventService,
    @org.springframework.beans.factory.annotation.Autowired(required = false) private val authContext: com.giwon.signaldesk.features.auth.application.AuthContext? = null,
    @org.springframework.beans.factory.annotation.Autowired(required = false) private val tickers: com.giwon.signaldesk.features.workspace.application.UserWatchTickerRepository? = null,
) {

    @GetMapping("/upcoming")
    fun upcoming(
        @RequestParam(defaultValue = "14") days: Int,
        @org.springframework.web.bind.annotation.RequestHeader("Authorization", required = false) auth: String? = null,
    ): MarketEventsResponse {
        val userId = authContext?.optionalUserId(auth)
        val watched = if (userId == null) emptySet() else tickers?.tickersForUser(userId, "US").orEmpty()
        val snapshot = service.upcomingSnapshot(days, watched)
        return MarketEventsResponse(true, snapshot.events, snapshot.earningsStatus)
    }

    @GetMapping("/earnings-status")
    fun earningsStatus() = ApiResponse(true, service.earningsStatus())
}

data class MarketEventsResponse(val success: Boolean, val data: List<MarketEvent>,
    val earningsStatus: com.giwon.signaldesk.features.events.application.EarningsCalendarResult)
