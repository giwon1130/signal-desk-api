package com.giwon.signaldesk.features.ai.application

import org.springframework.stereotype.Component
import java.time.Instant

@Component
class AiPickAssembler(private val tradePlanFactory: TradePlanFactory) {
    fun assemble(picks: List<AiPick>, candidates: List<PickCandidate>, generatedAt: Instant): List<AiPick> {
        val byTicker = candidates.groupBy { it.ticker.uppercase() }
        val byName = candidates.groupBy { it.name.trim() }
        return picks.mapNotNull { pick ->
            val raw = pick.ticker.trim().uppercase()
            val candidate = byTicker[raw]?.singleOrNull()
                ?: (if (raw.all(Char::isDigit) && raw.isNotEmpty()) byTicker[raw.padStart(6, '0')]?.singleOrNull() else null)
                ?: byName[pick.name.trim()]?.singleOrNull()
                ?: return@mapNotNull null
            val assessment = PickAssessmentPolicy.assess(candidate)
            pick.copy(
                market = candidate.market, ticker = candidate.ticker, name = candidate.name,
                changeRate = candidate.changeRate?.takeIf(Double::isFinite), flowTag = candidate.flowTag,
                expectedReturnRate = null,
                assessment = assessment,
                riskNote = (assessment.blockers + listOf(pick.riskNote)).filter { it.isNotBlank() }.joinToString(" · "),
                tradePlan = tradePlanFactory.build(candidate, generatedAt),
            )
        }.distinctBy { "${it.market}:${it.ticker}" }
            .sortedBy { it.assessment?.decision?.ordinal ?: Int.MAX_VALUE }
    }
}
