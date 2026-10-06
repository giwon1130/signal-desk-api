package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class YahooCandleClientTest {
    private val mapper = ObjectMapper()
    private val client = YahooCandleClient(mapper, false, "http://127.0.0.1:9")
    private val now = Instant.parse("2026-10-06T15:00:00Z")
    private fun payload() = mapper.readTree("""{"meta":{"symbol":"^GSPC","currency":"USD","exchangeTimezoneName":"America/New_York"},
      "timestamp":[${Instant.parse("2026-10-05T13:30:00Z").epochSecond},${Instant.parse("2026-10-06T13:30:00Z").epochSecond}],
      "indicators":{"quote":[{"open":[100,102],"high":[105,107],"low":[99,101],"close":[103,106],"volume":[1000,1500]}]}}""")
    @Test fun `preserve real OHLC volume exchange dates and provisional marker`() {
        val rows=client.parse(payload(),"^GSPC",now)
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).isEqualTo(IndexCandle("20261005",100.0,105.0,99.0,103.0,1000,false))
        assertThat(rows[1].provisional).isTrue()
        assertThat(client.parse(payload(),"^IXIC",now)).isEmpty()
    }
    @Test fun `missing volume or invalid OHLC must not turn into invented candle`() {
        val root=payload()
        (root["indicators"]["quote"][0]["volume"] as com.fasterxml.jackson.databind.node.ArrayNode).setNull(0)
        (root["indicators"]["quote"][0]["high"] as com.fasterxml.jackson.databind.node.ArrayNode).set(1,mapper.nodeFactory.numberNode(1))
        assertThat(client.parse(root,"^GSPC",now)).isEmpty()
    }
    @Test fun `duplicate timestamps and future bars are excluded`() {
        val root=payload()
        val timestamps=root["timestamp"] as com.fasterxml.jackson.databind.node.ArrayNode
        timestamps.set(1,timestamps[0])
        assertThat(client.parse(root,"^GSPC",now)).isEmpty()
        assertThat(client.parse(payload(),"^GSPC",now.minusSeconds(3*86400))).isEmpty()
    }
    @Test fun `weekly monthly candles aggregate observations not latest quote`() {
        val rows=client.parse(payload(),"^GSPC",now)
        val periods=usChartPeriods(rows,LocalDate.parse("2026-10-06"))
        val weekly=periods.first{it.key=="W"}.points.single()
        assertThat(weekly.open).isEqualTo(100.0)
        assertThat(weekly.close).isEqualTo(106.0)
        assertThat(weekly.high).isEqualTo(107.0)
        assertThat(weekly.low).isEqualTo(99.0)
        assertThat(weekly.volume).isEqualTo(2500)
        assertThat(weekly.provisional).isTrue()
        assertThat(periods).allMatch { it.source=="Yahoo Finance" && it.priceBasis=="PROVIDER_OHLC" }
    }
    @Test fun `failure and disabled provider leave honest empty charts`() {
        assertThat(client.fetch("^GSPC")).isEmpty()
        assertThat(usChartPeriods(emptyList())).allMatch { it.points.isEmpty() }
    }
}
