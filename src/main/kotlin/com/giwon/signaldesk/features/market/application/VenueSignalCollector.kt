package com.giwon.signaldesk.features.market.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class VenueSignalCollector(
    @Value("\${signal-desk.integrations.venue-signals.enabled:false}") private val enabled: Boolean,
) {
    private val trackedVenues = listOf(
        VenueSignalTarget(
            id = "freddies-beach-bar",
            name = "Freddie's Beach Bar",
            anchor = "Pentagon",
            distanceMiles = 1.4,
            category = "bar",
            sourceHint = "manual-proxy",
        ),
        VenueSignalTarget(
            id = "the-little-gay-pub",
            name = "The Little Gay Pub",
            anchor = "White House",
            distanceMiles = 1.1,
            category = "bar",
            sourceHint = "manual-proxy",
        ),
    )

    fun collect(): VenueSignalSnapshot {
        return VenueSignalSnapshot(
            enabled = enabled,
            venues = trackedVenues,
        )
    }
}

data class VenueSignalSnapshot(
    val enabled: Boolean,
    val venues: List<VenueSignalTarget>,
)

data class VenueSignalTarget(
    val id: String,
    val name: String,
    val anchor: String,
    val distanceMiles: Double,
    val category: String,
    val sourceHint: String,
)
