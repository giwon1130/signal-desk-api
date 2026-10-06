package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.springframework.stereotype.Component
import java.time.*

data class SnapshotSession(val market: String, val date: LocalDate, val closesAt: Instant) {
    val currency: String get() = if (market == "KR") "KRW" else "USD"
}

/** Fail closed outside the verified calendar and capture window. No historical holdings backfill. */
@Component
class SnapshotSessionPolicy(private val sessions: MarketSessionService) {
    fun target(market: String, now: Instant): SnapshotSession? {
        require(market in setOf("KR", "US")) { "market must be KR or US" }
        val zone = ZoneId.of(if (market == "KR") "Asia/Seoul" else "America/New_York")
        val local = now.atZone(zone)
        // Yahoo marks the current exchange-date candle provisional. Capture after that date ends.
        // Saturday is intentional: Friday's US close must not be lost to a weekday-only KST cron.
        if (market == "US" && local.toLocalTime() >= LocalTime.of(9, 0)) return null
        val date = local.toLocalDate().minusDays(if (market == "US") 1 else 0)
        val session = sessions.upcomingSessions(date.atStartOfDay(zone).toInstant().minusSeconds(1), 2).sessions
            .singleOrNull { it.market == market && it.tradingDate == date.toString() } ?: return null
        val close = Instant.parse(session.closesAt)
        if (now < close.plusSeconds(3600)) return null
        return SnapshotSession(market, date, close)
    }
}
