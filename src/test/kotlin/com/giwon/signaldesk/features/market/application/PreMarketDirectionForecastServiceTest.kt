package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import java.time.*

class PreMarketDirectionForecastServiceTest {
    private val date = LocalDate.parse("2026-09-10")
    private val beforeOpen = Instant.parse("2026-09-09T23:50:00Z")
    private val jdbc = mock(JdbcTemplate::class.java)
    private val direction = mock(PreMarketDirectionService::class.java)
    private val charts = mock(NaverIndexChartClient::class.java)
    private fun service(clock: Clock) = PreMarketDirectionForecastService(jdbc, jacksonObjectMapper(), direction, MarketSessionService(), charts, clock)
    private fun validDirection() = PreMarketDirection.EMPTY.copy(bias = "RISING", score = 1.0,
        confidence = "LOW", coverage = 90, inputCount = 5, asOf = beforeOpen.toString())

    @Test fun `capture refuses historical dates after open and holidays without fetching data`() {
        assertThat(service(Clock.fixed(beforeOpen, ZoneOffset.UTC)).capture(date.minusDays(1)).saved).isFalse()
        assertThat(service(Clock.fixed(beforeOpen.plusSeconds(600), ZoneOffset.UTC)).capture(date).saved).isFalse()
        val holiday = Instant.parse("2026-08-16T23:50:00Z")
        assertThat(service(Clock.fixed(holiday, ZoneOffset.UTC)).capture().saved).isFalse()
        verifyNoInteractions(jdbc, direction, charts)
    }

    @Test fun `a slow fetch crossing market open is not saved as a preopen prediction`() {
        var calls = 0
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant() = if (calls++ == 0) beforeOpen else beforeOpen.plusSeconds(601)
        }
        `when`(direction.current()).thenReturn(validDirection())
        assertThat(service(clock).capture(date).saved).isFalse()
        verifyNoInteractions(jdbc)
    }

    @Test fun `capture records version and first timestamp without overwriting earlier result`() {
        `when`(direction.current()).thenReturn(validDirection())
        `when`(direction.rulesVersion).thenReturn("test-rule-v2")
        val result = service(Clock.fixed(beforeOpen, ZoneOffset.UTC)).capture(date)
        assertThat(result.saved).isFalse() // JDBC update count 0 means conflict/no insert, not success
        val call = mockingDetails(jdbc).invocations.single()
        assertThat(call.arguments[0].toString()).contains("on conflict (prediction_date) do nothing", "rules_version")
        assertThat(call.arguments[0].toString()).doesNotContain("do update", "now()")
        assertThat(call.arguments.joinToString()).contains("test-rule-v2")
    }

    @Test fun `missing or future input timestamp is not eligible for directional capture`() {
        `when`(direction.current()).thenReturn(validDirection().copy(asOf = null))
        assertThat(service(Clock.fixed(beforeOpen, ZoneOffset.UTC)).capture(date).saved).isFalse()
        `when`(direction.current()).thenReturn(validDirection().copy(asOf = beforeOpen.plusSeconds(1).toString()))
        assertThat(service(Clock.fixed(beforeOpen, ZoneOffset.UTC)).capture(date).saved).isFalse()
        verifyNoInteractions(jdbc)
    }

    @Test fun `stats only read current rules version and bounded sample window`() {
        `when`(direction.rulesVersion).thenReturn("test-rule-v2")
        service(Clock.fixed(beforeOpen, ZoneOffset.UTC)).stats(999)
        val call = mockingDetails(jdbc).invocations.single()
        assertThat(call.arguments[0].toString()).contains("rules_version = ?", "recorded_at <", "09:00")
        assertThat(call.arguments.joinToString()).contains("test-rule-v2", "90")
    }

    @Test fun `evaluation before opening or without a frozen forecast skips price retrieval`() {
        assertThat(service(Clock.fixed(beforeOpen, ZoneOffset.UTC)).evaluate(date).evaluated).isFalse()
        `when`(direction.rulesVersion).thenReturn("test-rule-v2")
        assertThat(service(Clock.fixed(beforeOpen.plusSeconds(1200), ZoneOffset.UTC)).evaluate(date).evaluated).isFalse()
        verifyNoInteractions(charts)
        assertThat(mockingDetails(jdbc).invocations.single().arguments[0].toString())
            .contains("rules_version = ?", "evaluated_at is null", "recorded_at <")
    }

    @Test
    fun `시초 갭이 0점05퍼센트 이상이면 상승으로 분류한다`() {
        assertThat(PreMarketDirectionForecastService.biasOf(0.05)).isEqualTo(PreMarketDirectionService.Bias.RISING)
        assertThat(PreMarketDirectionForecastService.biasOf(0.04)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }

    @Test
    fun `시초 갭이 마이너스 0점05퍼센트 이하이면 하락으로 분류한다`() {
        assertThat(PreMarketDirectionForecastService.biasOf(-0.05)).isEqualTo(PreMarketDirectionService.Bias.FALLING)
        assertThat(PreMarketDirectionForecastService.biasOf(-0.04)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }
}
