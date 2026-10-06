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
    private var cached: MarketInsightAnalysis? = null
    private var lastRewriteAt = Instant.EPOCH
    @Synchronized fun current(): MarketInsightAnalysis {
        val report = evidence.current()
        cached?.takeIf { it.assessment == report }?.let { return it }
        val facts = narrator.narrativeFacts(report)
        val previous = cached
        val result = when {
            previous?.assessment?.let { narrator.narrativeFacts(it) == facts } == true -> narrator.render(report, previous.summary)
            Duration.between(lastRewriteAt, Instant.now()) < Duration.ofMinutes(15) ->
                narrator.render(report, narrator.fallbackSummary(facts))
            else -> narrator.narrate(report).also { lastRewriteAt = Instant.now() }
        }
        return result.also { cached = it }
    }
}
