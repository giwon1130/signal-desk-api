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
class MarketEventController(private val service: MarketEventService) {

    @GetMapping("/upcoming")
    fun upcoming(
        @RequestParam(defaultValue = "14") days: Int,
    ): ApiResponse<List<MarketEvent>> = ApiResponse(true, service.upcoming(days))

    @GetMapping("/today")
    fun today(): ApiResponse<List<MarketEvent>> = ApiResponse(true, service.today())
}
