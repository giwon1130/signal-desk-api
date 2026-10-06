package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.MarketEvidenceService
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.Duration

/** Narration belongs to the exact assessed snapshot, not an unrelated long-lived briefing TTL. */
@Service
class EvidenceBriefingService(
    private val evidence: MarketEvidenceService,
    private val narrator: EvidenceNarrator,
) {
    private val cache = mutableMapOf<String, MarketInsightAnalysis>()
    private val rewrites = mutableMapOf<String, Instant>()
    @Synchronized fun current(market: String = "KR"): MarketInsightAnalysis {
        val report = evidence.current(market)
        val cached = cache[market]
        val lastRewriteAt = rewrites[market] ?: Instant.EPOCH
        cached?.takeIf { it.assessment == report }?.let { return it }
        val facts = narrator.narrativeFacts(report)
        val previous = cached
        val result = when {
            previous?.assessment?.let { narrator.narrativeFacts(it) == facts } == true -> narrator.render(report, previous.summary)
            Duration.between(lastRewriteAt, Instant.now()) < Duration.ofMinutes(15) ->
                narrator.render(report, narrator.fallbackSummary(facts))
            else -> narrator.narrate(report).also { rewrites[market] = Instant.now() }
        }
        return result.also { cache[market] = it }
    }
}
