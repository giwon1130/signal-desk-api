package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FredIndexClientTest {
    @Test fun `dates and finite observations survive missing values sorting and duplicates`() {
        val rows = FredIndexClient(false, "http://unused").parseCsvRows("""
            DATE,DGS10
            2026-09-09,4.30
            2026-09-08,4.20
            2026-09-07,.
            2026-09-06,NaN
            invalid,4.2
            2026-09-09,4.30
        """.trimIndent())
        assertThat(rows.map { it.first.toString() }).containsExactly("2026-09-08", "2026-09-09")
        assertThat(rows.map { it.second }).containsExactly(4.2, 4.3)
    }
}
