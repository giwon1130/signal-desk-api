package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.*

class KrxNightFuturesFeedTest {
    private val sessions = MarketSessionService()
    private val evidence = KrxNightFuturesEvidence(sessions)
    private val decoder = KisNightFuturesDecoder()
    private val start = LocalDate.parse("2026-09-09")
    private val expiry = LocalDate.parse("2026-09-10")
    private fun at(local: String) = LocalDateTime.parse(local).atZone(KisNightFuturesDecoder.KOREA).toInstant()
    private fun fields(time: String = "055959", down: Boolean = false) = MutableList(49) { "0" }.apply {
        this[0] = "TEST_CONTRACT"; this[1] = time; this[2] = "4.0"; this[3] = if (down) "5" else "2"
        this[4] = if (down) "-1.0" else "1.0"; this[5] = if (down) "396.0" else "404.0"
    }
    private fun quote() = decoder.decode(fields(), start, at("2026-09-10T06:00:00"), "TEST_CONTRACT", expiry)!!

    @Test fun `official column mapping preserves price signed return and provider source`() {
        val q = quote()
        assertThat(q.value).isEqualTo(404.0)
        assertThat(q.previousValue).isEqualTo(400.0)
        assertThat(q.changeRate).isEqualTo(1.0)
        assertThat(q.observedAt).isEqualTo(at("2026-09-10T05:59:59"))
        assertThat(q.source).isEqualTo("KIS:H0MFCNT0")
        assertThat(decoder.decode(fields(down = true), start, q.receivedAt, "TEST_CONTRACT", expiry)?.changeRate).isEqualTo(-1.0)
    }

    @Test fun `session anchor resolves midnight without treating received time as quote time`() {
        val q = decoder.decode(fields("235959"), start, at("2026-09-10T00:00:01"), "TEST_CONTRACT", expiry)!!
        assertThat(q.observedAt).isEqualTo(at("2026-09-09T23:59:59"))
        assertThat(q.observedAt).isNotEqualTo(q.receivedAt)
    }

    @Test fun `wrong contract expiry unknown sign and column drift fail closed`() {
        val received = quote().receivedAt
        assertThat(decoder.decode(fields(), start, received, "OTHER", expiry)).isNull()
        assertThat(decoder.decode(fields(), start, received, "TEST_CONTRACT", start)).isNull()
        assertThat(decoder.decode(fields().apply { this[3] = "?" }, start, received, "TEST_CONTRACT", expiry)).isNull()
        assertThat(decoder.decode(fields().dropLast(1), start, received, "TEST_CONTRACT", expiry)).isNull()
    }

    @Test fun `inconsistent nonfinite future and old wire values fail closed`() {
        val received = quote().receivedAt
        for (bad in listOf(fields().apply { this[4] = "5" }, fields().apply { this[5] = "NaN" },
            fields("120000"), fields("240000"), fields("020000"))) {
            assertThat(decoder.decode(bad, start, received, "TEST_CONTRACT", expiry)).isNull()
        }
        assertThat(decoder.decode(fields(), start, received.minusSeconds(60), "TEST_CONTRACT", expiry)).isNull()
    }

    @Test fun `valid final night observation contributes exactly ten percent of coverage`() {
        val report = MarketEvidenceAnalyzer(sessions).analyze(MarketEvidenceInput(at("2026-09-10T08:30:00"), emptyList(), null, null, quote()))
        assertThat(report.evidence.single { it.id == "KR_NIGHT" }.status).isEqualTo("OBSERVED")
        assertThat(report.coveragePercent).isEqualTo(10)
        assertThat(report.regime).isEqualTo("INSUFFICIENT_DATA")
        assertThat(report.factors.single { it.id == "kr_night" }.score).isEqualTo(1.0)
    }

    @Test fun `night observation is not carried into regular day as current evidence`() {
        assertThat(evidence.assess(quote(), at("2026-09-10T09:00:00")).status).isEqualTo("STALE")
        assertThat(evidence.assess(quote().copy(observedAt = at("2026-09-10T02:00:00"), receivedAt = at("2026-09-10T02:00:01")),
            at("2026-09-10T08:30:00")).status).isEqualTo("STALE")
    }

    @Test fun `Friday night continues Saturday and remains latest before Monday open`() {
        val friday = LocalDate.parse("2026-09-11")
        val q = decoder.decode(fields(), friday, at("2026-09-12T06:00:00"), "TEST_CONTRACT", LocalDate.parse("2026-12-10"))!!
        assertThat(evidence.assess(q, at("2026-09-12T06:00:01")).status).isEqualTo("OBSERVED")
        assertThat(evidence.assess(q, at("2026-09-14T08:30:00")).status).isEqualTo("OBSERVED")
        assertThat(evidence.assess(q, at("2026-09-14T18:00:00")).status).isEqualTo("STALE")
    }

    @Test fun `holiday follows start day so night before holiday is valid but holiday evening is not`() {
        val before = LocalDate.parse("2026-09-23")
        val q = decoder.decode(fields(), before, at("2026-09-24T06:00:00"), "TEST_CONTRACT", LocalDate.parse("2026-12-10"))!!
        assertThat(evidence.assess(q, at("2026-09-24T08:30:00")).status).isEqualTo("OBSERVED")
        val invalid = q.copy(sessionStartDate = before.plusDays(1), observedAt = q.observedAt.plus(Duration.ofDays(1)), receivedAt = q.receivedAt.plus(Duration.ofDays(1)))
        assertThat(evidence.assess(invalid, at("2026-09-25T08:30:00")).status).isEqualTo("INVALID")
    }

    @Test fun `unknown future calendar and spoofed provider are excluded`() {
        assertThat(evidence.assess(quote().copy(source = "Yahoo:EWY"), at("2026-09-10T08:30:00")).status).isEqualTo("INVALID")
        assertThat(evidence.assess(quote(), at("2027-01-04T08:30:00")).status).isEqualTo("UNDATED")
    }

    @Test fun `previously missing official holidays are closed`() {
        listOf("2026-05-01", "2026-06-03", "2026-08-17").forEach {
            assertThat(sessions.isKrTradingDay(LocalDate.parse(it))).isFalse()
        }
    }
}
