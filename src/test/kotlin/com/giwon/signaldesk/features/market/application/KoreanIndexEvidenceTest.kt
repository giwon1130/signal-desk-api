package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class KoreanIndexEvidenceTest {
    private val decoder = KoreanIndexEvidence(MarketSessionService())
    private val now = Instant.parse("2026-10-12T13:30:00Z")
    private val q = IndexQuote("KOSPI", "코스피", 6384.24, -241.69, -3.65, "CLOSE", "2026-10-12T20:15:00+09:00")
    @Test fun `previous trading date crosses Korean holiday and weekend`() {
        val result = decoder.convert(q, now)!!
        assertThat(result.previousObservationDate).isEqualTo("2026-10-08")
        assertThat(result.changeRate).isEqualTo(-3.65)
        assertThat(result.previousValue).isCloseTo(6625.93, org.assertj.core.data.Offset.offset(.000001))
        assertThat(result.source).isEqualTo("NAVER:KOSPI")
    }
    @Test fun `inconsistent missing future and unverified values fail closed`() {
        for (invalid in listOf(q.copy(changeRate = 3.65), q.copy(tradedAt = ""), q.copy(price = Double.NaN),
            q.copy(tradedAt = "2026-10-13T15:30:00+09:00"), q.copy(marketStatus = "UNKNOWN"))) {
            assertThat(decoder.convert(invalid, now)).isNull()
        }
    }
}
