package com.giwon.signaldesk.features.workspace.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SignalDeskJdbcPropertiesTest {

    @Test
    fun `keeps jdbc url as is`() {
        val properties = SignalDeskJdbcProperties(
            url = "jdbc:postgresql://db.railway.internal:5432/railway",
        )

        assertEquals("jdbc:postgresql://db.railway.internal:5432/railway", properties.resolveJdbcUrl())
    }

    @Test
    fun `normalizes postgres url to jdbc url`() {
        val properties = SignalDeskJdbcProperties(
            url = "postgresql://db.railway.internal:5432/railway",
        )

        assertEquals("jdbc:postgresql://db.railway.internal:5432/railway", properties.resolveJdbcUrl())
    }

    @Test
    fun `builds jdbc url from pg variables`() {
        val properties = SignalDeskJdbcProperties(
            host = "postgres.railway.internal",
            port = 5432,
            database = "railway",
        )

        assertEquals("jdbc:postgresql://postgres.railway.internal:5432/railway", properties.resolveJdbcUrl())
    }

    @Test
    fun `fails when no jdbc connection info exists`() {
        val properties = SignalDeskJdbcProperties()

        assertThrows(IllegalArgumentException::class.java) {
            properties.resolveJdbcUrl()
        }
    }
}
