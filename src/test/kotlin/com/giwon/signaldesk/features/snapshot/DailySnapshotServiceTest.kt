package com.giwon.signaldesk.features.snapshot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.features.ai.application.*
import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.*

class DailySnapshotServiceTest {
    private val jdbc = mock(JdbcTemplate::class.java)
    private val picks = mock(AiPickService::class.java)
    private val prices = mock(SnapshotClosePrices::class.java)
    private val now = Instant.parse("2026-10-06T04:40:00Z")
    private val policy = SnapshotSessionPolicy(MarketSessionService())
    private val session = policy.target("US",now)!!
    private val service = DailySnapshotService(jdbc,jacksonObjectMapper(),picks,policy,prices,Clock.fixed(now,ZoneOffset.UTC))
    private fun pick(ticker: String, date: String="2026-10-05", decision: PickDecision=PickDecision.REVIEW, market: String="US") =
        AiPick(market,ticker,ticker,"검토 후보입니다.",null,0,"매수 권유가 아닙니다.",
            assessment=PickAssessment(decision,emptyList(),emptyList(),"rules-test-v1",date))
    private fun writes() = mockingDetails(jdbc).invocations.filter { it.method.name=="update" }

    @Test fun `outside capture window does not fetch data or write anything`() {
        val other = DailySnapshotService(jdbc,jacksonObjectMapper(),picks,policy,prices,
            Clock.fixed(Instant.parse("2026-10-05T21:00:00Z"),ZoneOffset.UTC))
        assertThat(other.runDailySnapshot("US")).isEqualTo(DailySnapshotService.Result(false,0,0))
        verifyNoInteractions(jdbc,picks,prices)
    }

    @Test fun `only matching US review picks recorded with reference close not executable entry`() {
        `when`(prices.indices(session)).thenReturn(mapOf("^IXIC" to SnapshotClose(BigDecimal("20000.12"),"YAHOO_FINANCE"),
            "^GSPC" to SnapshotClose(BigDecimal("6000.23"),"YAHOO_FINANCE")))
        `when`(picks.getTodayPicks()).thenReturn(AiPicksResponse(now.minusSeconds(30).toString(),"",listOf(
            pick("AAPL"),pick("OLD","2026-10-02"),pick("WATCH",decision=PickDecision.WATCH),pick("005930",market="KR"),pick("MISSING"))))
        `when`(prices.stock("AAPL",session)).thenReturn(SnapshotClose(BigDecimal("101.23"),"YAHOO_FINANCE"))
        service.runDailySnapshot("US")
        val calls = writes()
        assertThat(calls).hasSize(2)
        val market = calls.first()
        assertThat(market.arguments[0].toString()).contains("signal_desk_market_close_snapshot","on conflict (market, trading_date) do nothing")
        assertThat(market.arguments[1]).isEqualTo("US")
        assertThat(market.arguments[2].toString()).isEqualTo("2026-10-05")
        val history = calls.last()
        assertThat(history.arguments[0].toString()).contains("SESSION_CLOSE_REFERENCE_ONLY","0, null, null", "on conflict (pick_date, market, ticker) do nothing")
        assertThat(history.arguments.toList()).contains("AAPL","rules-test-v1",BigDecimal("101.23"),"USD")
        verify(prices,never()).stock("OLD",session)
        verify(prices,never()).stock("005930",session)
        assertThat(mockingDetails(jdbc).invocations.first { it.method.name=="query" }.arguments[0].toString())
            .contains("market = ?").doesNotContain("current_price")
    }

    @Test fun `old cached analysis and incomplete market closes cause no snapshot writes`() {
        `when`(prices.indices(session)).thenReturn(mapOf("^IXIC" to SnapshotClose(BigDecimal.ONE,"YAHOO_FINANCE")))
        `when`(picks.getTodayPicks()).thenReturn(AiPicksResponse(now.minusSeconds(7200).toString(),"",listOf(pick("AAPL"))))
        service.runDailySnapshot("US")
        assertThat(writes()).isEmpty()
        verify(prices,never()).stock("AAPL",session)
    }

    @Test fun `provider outage does not prevent other snapshot sections running`() {
        `when`(prices.indices(session)).thenThrow(IllegalStateException("offline"))
        `when`(picks.getTodayPicks()).thenReturn(AiPicksResponse(now.toString(),"",listOf(pick("AAPL"))))
        `when`(prices.stock("AAPL",session)).thenReturn(SnapshotClose(BigDecimal("101.23"),"YAHOO_FINANCE"))
        service.runDailySnapshot("US")
        assertThat(writes()).hasSize(1)
        assertThat(writes().single().arguments[0].toString()).contains("signal_desk_ai_pick_history")
    }
}
