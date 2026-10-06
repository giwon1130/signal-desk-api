package com.giwon.signaldesk.features.ai.application

import com.giwon.signaldesk.features.market.application.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class AiPickServiceTest {
    private val movers=mock(TopMoversService::class.java)
    private val flows=mock(NaverInvestorRankClient::class.java)
    private val candles=mock(YahooCandleClient::class.java)
    private val kr=mock(NaverStockChartClient::class.java)
    private val screener=mock(YahooFinanceScreenerClient::class.java)
    private val executor=Executors.newFixedThreadPool(2)
    private val service=AiPickService(movers,flows,candles,kr,screener,MarketSessionService(),executor)
    private val now=Instant.parse("2026-10-06T14:00:00Z")
    @org.junit.jupiter.api.AfterEach fun close() { executor.shutdownNow() }

    @Test fun `without Gemini select bounded candidates from completed sessions and never emit executable plans`() {
        `when`(screener.fetchMostActives(8)).thenReturn((1..12).map { YahooQuote("T$it","Stock $it",104.0,2.0,1000000,1000000,"NYSE") })
        val dates=generateSequence(LocalDate.parse("2026-10-05")) { it.minusDays(1) }
            .filter { it.dayOfWeek.value<6 }.take(21).toList().reversed()
        val bars=dates.mapIndexed { i, date -> IndexCandle(date.format(DateTimeFormatter.BASIC_ISO_DATE),100.0+i*.2,101.0+i*.2,99.0+i*.2,100.0+i*.2,
            if(i==20) 2000000 else 1000000) }
        `when`(candles.fetch(anyString(),anyString())).thenReturn(bars)
        val response=service.generate(now)
        assertThat(response.picks).hasSize(8)
        assertThat(response.picks).allMatch { it.assessment?.decision==PickDecision.REVIEW && it.tradePlan==null && it.expectedReturnRate==null }
        assertThat(response.picks).allMatch { it.assessment?.analysisDate=="2026-10-05" && it.confidence==0 }
        verify(candles,times(8)).fetch(anyString(),anyString())
    }

    @Test fun `missing history remains data shortage even if screener reports a rally`() {
        `when`(screener.fetchMostActives(8)).thenReturn(listOf(YahooQuote("TEST","Test",100.0,20.0,1000000,null,"NYSE")))
        `when`(candles.fetch(anyString(),anyString())).thenReturn(emptyList())
        val pick=service.generate(now).picks.single()
        assertThat(pick.assessment!!.decision).isEqualTo(PickDecision.INSUFFICIENT_DATA)
        assertThat(pick.tradePlan).isNull()
    }

    @Test fun `a source outage produces an empty response rather than invented picks`() {
        `when`(movers.fetchTopMovers(10)).thenThrow(IllegalStateException("offline"))
        `when`(flows.fetchFlowSnapshot(7)).thenThrow(IllegalStateException("offline"))
        `when`(screener.fetchMostActives(8)).thenThrow(IllegalStateException("offline"))
        assertThat(service.generate(now).picks).isEmpty()
    }
}
