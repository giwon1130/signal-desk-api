package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.features.market.application.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SnapshotClosePricesTest {
    private val krIndices = mock(NaverIndexChartClient::class.java)
    private val krStocks = mock(NaverStockChartClient::class.java)
    private val us = mock(YahooCandleClient::class.java)
    private val prices = SnapshotClosePrices(krIndices, krStocks, us)
    private val session = SnapshotSession("US", LocalDate.parse("2026-10-05"), Instant.parse("2026-10-05T20:00:00Z"))
    private val bar = IndexCandle("20261005", 100.0, 102.0, 99.0, 101.23, 10000)

    @Test fun `accept exactly one dated validated non provisional close preserving cents`() {
        assertThat(prices.select(listOf(bar), session)).isEqualTo(SnapshotClose(BigDecimal("101.23"), "YAHOO_FINANCE"))
        for (invalid in listOf(bar.copy(date="20261002"), bar.copy(provisional=true), bar.copy(close=Double.NaN),
            bar.copy(close=0.0), bar.copy(high=100.0), bar.copy(low=102.0))) {
            assertThat(prices.select(listOf(invalid), session)).isNull()
        }
        assertThat(prices.select(listOf(bar,bar), session)).isNull()
        assertThat(prices.select(emptyList(),session)).isNull()
    }

    @Test fun `route class shares exactly and isolate provider failure`() {
        `when`(us.fetch("BRK-B", "6mo")).thenReturn(listOf(bar))
        assertThat(prices.stock("BRK.B", session)?.price).isEqualByComparingTo("101.23")
        verify(us).fetch("BRK-B", "6mo")
        `when`(us.fetch("FAIL", "6mo")).thenThrow(IllegalStateException("offline"))
        assertThat(prices.stock("FAIL", session)).isNull()
        assertThat(prices.stock("../../secret", session)).isNull()
        verifyNoInteractions(krStocks,krIndices)
    }

    @Test fun `KR uses OHLC not integer truncating current quote`() {
        val krSession = session.copy(market="KR")
        `when`(krStocks.fetchDailyOhlc("005930",5)).thenReturn(listOf(OhlcBar("20261005",70000.0,71000.0,69000.0,70500.0)))
        assertThat(prices.stock("005930",krSession)?.price).isEqualByComparingTo("70500")
        assertThat(prices.stock("AAPL",krSession)).isNull()
        verifyNoInteractions(us)
    }

    @Test fun `fractional positions are valued exactly without cross currency aggregation`() {
        val user = UUID.randomUUID()
        val positions = listOf(SnapshotPosition(user,"AAPL",BigDecimal("0.125"),BigDecimal("100.01")),
            SnapshotPosition(user,"MSFT",BigDecimal("2.1"),BigDecimal("200.02")))
        val valuation = valueSnapshotPositions(positions) { ticker ->
            SnapshotClose(BigDecimal(if(ticker=="AAPL") "101.23" else "201.45"),"YAHOO_FINANCE")
        }!!
        assertThat(valuation.evaluation).isEqualByComparingTo("435.69875")
        assertThat(valuation.cost).isEqualByComparingTo("432.54325")
        assertThat(valuation.sources).containsExactly("YAHOO_FINANCE")
    }

    @Test fun `one missing price or invalid quantity rejects whole portfolio no partial or stored price fallback`() {
        val valid = SnapshotPosition(UUID.randomUUID(),"AAPL",BigDecimal.ONE,BigDecimal.TEN)
        val quote = SnapshotClose(BigDecimal("11.25"),"YAHOO_FINANCE")
        assertThat(valueSnapshotPositions(listOf(valid,valid.copy(ticker="MISSING"))) { if(it=="AAPL") quote else null }).isNull()
        for (invalid in listOf(valid.copy(quantity=BigDecimal.ZERO),valid.copy(quantity=null),valid.copy(buyPrice=BigDecimal("-1")))) {
            assertThat(valueSnapshotPositions(listOf(invalid)) { quote }).isNull()
        }
        assertThat(valueSnapshotPositions(emptyList()) { quote }).isNull()
    }
}
