package com.giwon.signaldesk.features.media.application

import org.springframework.stereotype.Service

/** On-demand and scheduled market commentary share one deterministic evidence assessment. */
@Service
class MarketInsightService(private val briefing: EvidenceBriefingService) {
    fun getTodayInsight(): MarketInsightAnalysis = briefing.current()
}
