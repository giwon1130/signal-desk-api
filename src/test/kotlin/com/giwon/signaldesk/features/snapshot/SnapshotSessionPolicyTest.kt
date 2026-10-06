package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class SnapshotSessionPolicyTest {
    private val policy = SnapshotSessionPolicy(MarketSessionService())
    private fun target(market: String, time: String) = policy.target(market, Instant.parse(time))

    @Test fun `KR close is separate from previous US exchange date on the same KST day`() {
        assertThat(target("KR", "2026-10-06T07:40:00Z")!!.date.toString()).isEqualTo("2026-10-06")
        val us = target("US", "2026-10-06T04:40:00Z")!!
        assertThat(us.date.toString()).isEqualTo("2026-10-05")
        assertThat(us.closesAt).isEqualTo(Instant.parse("2026-10-05T20:00:00Z"))
        assertThat(us.currency).isEqualTo("USD")
    }

    @Test fun `reject early capture KR holidays and unverified special hours`() {
        assertThat(target("KR", "2026-10-06T06:31:00Z")).isNull()
        assertThat(target("KR", "2026-10-05T07:40:00Z")).isNull()
        assertThat(target("KR", "2026-09-25T07:40:00Z")).isNull()
        assertThat(target("KR", "2026-11-19T08:40:00Z")).isNull()
        assertThat(target("KR", "2027-01-04T07:40:00Z")).isNull()
    }

    @Test fun `US Friday captured Saturday but weekend and holiday closes are never invented`() {
        assertThat(target("US", "2026-10-03T04:40:00Z")!!.date.toString()).isEqualTo("2026-10-02")
        assertThat(target("US", "2026-10-04T04:40:00Z")).isNull()
        assertThat(target("US", "2026-10-05T04:40:00Z")).isNull()
        assertThat(target("US", "2026-11-27T05:40:00Z")).isNull()
    }

    @Test fun `US DST and official early close preserve correct UTC session end`() {
        assertThat(target("US", "2026-03-07T05:40:00Z")!!.closesAt).isEqualTo(Instant.parse("2026-03-06T21:00:00Z"))
        assertThat(target("US", "2026-03-10T04:40:00Z")!!.closesAt).isEqualTo(Instant.parse("2026-03-09T20:00:00Z"))
        assertThat(target("US", "2026-11-28T05:40:00Z")!!.closesAt).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"))
    }

    @Test fun `US capture avoids provisional same-day candles and next regular session`() {
        assertThat(target("US", "2026-10-05T21:00:00Z")).isNull()
        assertThat(target("US", "2026-10-06T13:00:00Z")).isNull()
        assertThatThrownBy { target("EU", "2026-10-06T04:40:00Z") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
