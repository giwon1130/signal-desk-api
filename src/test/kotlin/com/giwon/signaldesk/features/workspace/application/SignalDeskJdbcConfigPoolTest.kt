package com.giwon.signaldesk.features.workspace.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SignalDeskJdbcConfigPoolTest {

    @Test
    fun `pool releases every idle connection quickly`() {
        val properties = SignalDeskJdbcProperties(
            url = "postgresql://localhost:5432/signal_desk",
            username = "test",
            password = "test",
        )

        val config = SignalDeskJdbcConfig().buildHikariConfig(properties)

        assertEquals(5, config.maximumPoolSize)
        assertEquals(0, config.minimumIdle)
        assertEquals(60_000, config.idleTimeout)
    }
}
