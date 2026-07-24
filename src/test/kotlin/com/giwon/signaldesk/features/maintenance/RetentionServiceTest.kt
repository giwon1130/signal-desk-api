package com.giwon.signaldesk.features.maintenance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.jdbc.core.JdbcTemplate

class RetentionServiceTest {

    @Test
    fun `시장 라운드는 종료 후 14일이 지나면 정리한다`() {
        val jdbc = mock(JdbcTemplate::class.java)

        val deleted = RetentionService(jdbc).runRetention()

        val invocation = mockingDetails(jdbc).invocations.single {
            it.arguments.firstOrNull()?.toString()?.contains("signal_desk_market_rounds") == true
        }
        assertThat(invocation.arguments[0].toString()).contains("ends_at < now()")
        assertThat(invocation.arguments[1]).isEqualTo(RetentionService.MARKET_ROUND_DAYS)
        assertThat(RetentionService.MARKET_ROUND_DAYS).isEqualTo(14)
        assertThat(deleted).containsEntry("market_rounds", 0)
    }
}
