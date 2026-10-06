package com.giwon.signaldesk.features.events.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FinnhubClientTest {
    private val mapper=ObjectMapper()
    private val client=FinnhubClient(mapper,true,"http://127.0.0.1:9","")
    private val from=LocalDate.parse("2026-10-01")
    private val until=LocalDate.parse("2026-10-31")
    @Test fun `configuration failure and valid empty calendar remain distinct`() {
        assertThat(client.fetchCalendar(from.toString(),until.toString()).status).isEqualTo("NOT_CONFIGURED")
        assertThat(client.parse(mapper.readTree("{}"),from,until).status).isEqualTo("UNAVAILABLE")
        assertThat(client.parse(mapper.readTree("{\"earningsCalendar\":[]}"),from,until).status).isEqualTo("AVAILABLE")
    }
    @Test fun `actuals are not estimates and missing numeric values stay missing`() {
        val root=mapper.readTree("""{"earningsCalendar":[
          {"symbol":"JPM","date":"2026-10-15","hour":"bmo","quarter":3,"year":2026,"epsEstimate":4.2,"epsActual":null,"revenueActual":0},
          {"symbol":"BAD","date":"not-date"},{"symbol":"OLD","date":"2026-09-30"},
          {"symbol":"ZERO","date":"2026-10-16","epsActual":0,"epsEstimate":null}]}""")
        val entries=client.parse(root,from,until).entries
        assertThat(entries).hasSize(2)
        assertThat(entries[0].epsActual).isNull()
        assertThat(entries[0].epsEstimate).isEqualTo(4.2)
        assertThat(entries[0].revenueActual).isEqualTo(0.0)
        assertThat(entries[1].epsEstimate).isNull()
        assertThat(entries[1].epsActual).isEqualTo(0.0)
    }
}
