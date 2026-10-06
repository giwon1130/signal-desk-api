package com.giwon.signaldesk.features.events.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketStaticEventsTest {
    @Test fun `official future dates and KST times are retained with provenance`() {
        val events = MarketStaticEvents.ALL.associateBy { it.id }
        for ((id, date) in mapOf("cpi-2026-09" to "2026-10-14", "cpi-2026-10" to "2026-11-10",
            "pce-2026-09" to "2026-10-29", "fomc-2026-12" to "2026-12-10")) {
            assertThat(events[id]!!.date).isEqualTo(date)
            assertThat(events[id]!!.sourceUrl).startsWith("https://")
            assertThat(events[id]!!.verifiedAt).isEqualTo("2026-10-06")
        }
        assertThat(events["cpi-2026-09"]!!.time).isEqualTo("21:30 KST")
        assertThat(events["cpi-2026-10"]!!.time).isEqualTo("22:30 KST")
        assertThat(events["fomc-2026-12"]!!.time).isEqualTo("04:00 KST")
    }
}
