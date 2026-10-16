package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Optional only: per-stock evidence still loads on demand and scheduled briefs use their own inputs. */
@Component
@ConditionalOnProperty(
    prefix = "signal-desk.scheduling.mover-reason-warm",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class MoverReasonWarmupScheduler(
    private val service: MoverReasonService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 45 * 1000L)
    fun warm() {
        if (marketSessionService.buildMarketSessions().none { it.market in setOf("KR", "US") && it.phase == "REGULAR" }) return
        runCatching { service.reasons() }.onFailure { log.debug("mover context warm skipped", it) }
    }
}
