package com.giwon.signaldesk.features.events.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.LocalDate
import java.time.ZoneId

class MarketEventCoverageTest {
    private val client=mock(FinnhubClient::class.java)
    private val service=MarketEventService(MarketSessionService(),client)
    private val today=LocalDate.now(ZoneId.of("America/New_York")).toString()

    @Test fun `one shared request includes major sectors and only the current users additional tickers`() {
        val entries=listOf("JPM","NVDA","USERA","USERB").map { FinnhubEarning(it,today,"bmo",null,3,2026) }
        `when`(client.fetchCalendar(anyString(),anyString(),isNull())).thenReturn(EarningsCalendarResult("AVAILABLE","2026-10-06T00:00:00Z",entries))
        val a=service.upcomingSnapshot(14,setOf("USERA"))
        val b=service.upcomingSnapshot(14,setOf("USERB"))
        assertThat(a.events.filter{it.category==EventCategory.EARNINGS}.flatMap{it.tickers}).containsExactlyInAnyOrder("JPM","NVDA","USERA")
        assertThat(b.events.filter{it.category==EventCategory.EARNINGS}.flatMap{it.tickers}).containsExactlyInAnyOrder("JPM","NVDA","USERB")
        assertThat(a.earningsStatus.entries).isEmpty()
        assertThat(a.events.filter{it.category==EventCategory.EARNINGS}).allMatch{it.dateTimezone=="America/New_York" && it.earnings?.currency==null}
        verify(client,times(2)).fetchCalendar(anyString(),anyString(),isNull())
        verifyNoMoreInteractions(client)
    }
    @Test fun `failed calendar is surfaced separately from other valid events`() {
        `when`(client.fetchCalendar(anyString(),anyString(),isNull())).thenReturn(EarningsCalendarResult("UNAVAILABLE",null))
        val result=service.upcomingSnapshot()
        assertThat(result.earningsStatus.status).isEqualTo("UNAVAILABLE")
        assertThat(result.events).noneMatch { it.category==EventCategory.EARNINGS }
    }
}
