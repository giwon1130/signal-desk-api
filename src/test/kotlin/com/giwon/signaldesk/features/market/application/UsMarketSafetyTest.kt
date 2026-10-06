package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.features.push.application.AlertQuotePolicy
import com.giwon.signaldesk.features.push.application.AlertDirection
import com.giwon.signaldesk.features.push.application.WatchlistAlertDetector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ExecutorService

class UsMarketSafetyTest {
    private val sessions = MarketSessionService()
    private fun at(text: String) = Instant.parse(text)
    @Test fun `calendar respects holiday DST and early close`() {
        val calendar = sessions.upcomingSessions(at("2026-10-30T00:00:00Z"))
        assertThat(calendar.sessions.first { it.market == "US" && it.tradingDate == "2026-10-30" }.opensAt).isEqualTo("2026-10-30T13:30:00Z")
        assertThat(calendar.sessions.first { it.market == "US" && it.tradingDate == "2026-11-02" }.opensAt).isEqualTo("2026-11-02T14:30:00Z")
        assertThat(calendar.sessions.none { it.market == "US" && it.tradingDate == "2026-11-26" }).isTrue()
        val short = sessions.upcomingSessions(at("2026-11-27T00:00:00Z")).sessions.first { it.market == "US" }
        assertThat(short.closesAt).isEqualTo("2026-11-27T18:00:00Z")
        assertThat(short.earlyClose).isTrue()
        assertThat(sessions.isRegularSession("US", at("2026-11-27T17:59:00Z"))).isTrue()
        assertThat(sessions.isRegularSession("US", at("2026-11-27T18:00:00Z"))).isFalse()
        assertThat(sessions.isRegularSession("US", at("2026-10-06T13:15:00Z"))).isFalse()
    }
    @Test fun `KR holidays and unknown annual calendar are never scheduled`() {
        assertThat(sessions.upcomingSessions(at("2026-09-23T00:00:00Z")).sessions.filter { it.market == "KR" }.map { it.tradingDate })
            .doesNotContain("2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09")
        assertThat(sessions.upcomingSessions(at("2027-01-01T00:00:00Z")).sessions.none { it.market == "KR" }).isTrue()
    }
    @Test fun `US quote parser verifies exchange identity currency decimals and observed time`() {
        val mapper = jacksonObjectMapper()
        val client = NaverGlobalQuoteClient(mapper, true, "http://unused", mock(ExecutorService::class.java), mock(NaverStockSearchClient::class.java))
        val json = mapper.readTree("""{"symbolCode":"AAPL","reutersCode":"AAPL.O","currencyType":{"code":"USD"},"stockExchangeType":{"nationCode":"USA"},"closePrice":"204.15","fluctuationsRatio":"1.25","localTradedAt":"2026-10-06T10:00:00-04:00","marketStatus":"OPEN","delayTime":0}""")
        val now = at("2026-10-06T14:01:00Z")
        val quote = client.parseQuote(json, "AAPL", "AAPL.O", now)!!
        assertThat(quote.exactPrice).isEqualTo(204.15)
        assertThat(quote.quoteInfo!!.observedAt).isEqualTo("2026-10-06T14:00:00Z")
        assertThat(client.parseQuote(json, "MSFT", "AAPL.O", now)).isNull()
        assertThat(client.parseQuote(json, "AAPL", "AAPL.N", now)).isNull()
        assertThat(client.parseQuote(json, "AAPL", "AAPL.O", now.minusSeconds(3600))).isNull()
        assertThat(AlertQuotePolicy.usable("US", quote, now)).isTrue()
        assertThat(AlertQuotePolicy.usable("US", quote, now.plusSeconds(301))).isFalse()
        assertThat(AlertQuotePolicy.usable("US", quote.copy(quoteInfo = null), now)).isFalse()
        assertThat(AlertQuotePolicy.usable("US", quote.copy(quoteInfo = quote.quoteInfo!!.copy(session = "CLOSE")), now)).isFalse()
        assertThat(AlertQuotePolicy.usable("US", quote.copy(quoteInfo = quote.quoteInfo!!.copy(delayMinutes = 15)), now)).isFalse()
    }
    @Test fun `portfolio totals do not mix currencies or truncate fractions`() {
        val us = HoldingPosition("US", "AAPL", "Apple", 200.15, 201.25, 0.5, 0.55, 100.625, 0.0)
        val single = PortfolioValuation.summarize(listOf(us))
        assertThat(single.totalValue).isEqualTo(100.625)
        assertThat(single.totalCurrency).isEqualTo("USD")
        val mixed = PortfolioValuation.summarize(listOf(us, us.copy(market = "KR", ticker = "005930", buyPrice = 70000.0, currentPrice = 75000.0, quantity = 1.0)))
        assertThat(mixed.totalValue).isNull()
        assertThat(mixed.currencyTotals.map { it.currency }).containsExactly("KRW", "USD")
        assertThrows<IllegalArgumentException> { PortfolioValuation.validate("KR", 100.5, 1.0) }
        assertThrows<IllegalArgumentException> { PortfolioValuation.validate("US", Double.NaN, 1.0) }
    }
    @Test fun `US refresh changes watchlist portfolio and alert candidates with exact price`() {
        val refresh = WorkspaceQuoteRefresher(mock(NaverStockChartClient::class.java), TechnicalIndicatorCalculator())
        val quotes = mapOf("AAPL" to StockQuote("AAPL", 204, 6.0, 204.15))
        val result = refresh.refreshWatchlist(listOf(WatchItem("US", "AAPL", "Apple", 180.0, 0.0, "", "", "")), quotes)
        assertThat(result.single().price).isEqualTo(204.15)
        val row = WatchlistAlertDetector.WatchRow(UUID.randomUUID(), "US", "AAPL", "Apple", 6.0, currentPrice = 204.15)
        val candidate = WatchlistAlertDetector().detect(listOf(row), emptySet(), LocalDate.parse("2026-10-06")).single { it.direction == AlertDirection.UP }
        assertThat(candidate.currentPrice).isEqualTo(204.15)
    }
}
