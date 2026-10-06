package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class IndexChartFactoryTest {
    @Test fun `missing OHLC and undated closes never generate candles`() {
        val periods = buildIndexChartPeriodsFromOhlc(3000.0, .5, emptyList(), emptyList(), emptyList())
        assertThat(periods).hasSize(3)
        assertThat(periods.flatMap { it.points }).isEmpty()
        assertThat(buildIndexChartPeriods(3000.0, .5, List(90) { 3000.0 }).flatMap { it.points }).isEmpty()
    }
    @Test fun `live price never overwrites a completed candle`() {
        val candle = IndexCandle("20260420", 2700.0, 2710.0, 2685.0, 2705.0, 1000)
        val points = buildIndexChartPeriodsFromOhlc(9999.0, 99.0, listOf(candle), emptyList(), emptyList()).first().points
        assertThat(points.single().close).isEqualTo(2705.0)
        assertThat(points.single().high).isEqualTo(2710.0)
        assertThat(points.single().volume).isEqualTo(1000)
    }
    @Test fun `invalid and duplicate dates are excluded and real history is bounded`() {
        val candles = (0..119).map { i -> IndexCandle(LocalDate.parse("2026-01-01").plusDays(i.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE),
            100.0, 110.0, 90.0, 105.0, 1000) }
        val extra = candles + candles.last() + candles.first().copy(date = "invalid")
        val points = buildIndexChartPeriodsFromOhlc(100.0, 0.0, extra, emptyList(), emptyList()).first().points
        assertThat(points).hasSize(90)
        assertThat(points.last().label).isEqualTo("04/29")
    }
}
