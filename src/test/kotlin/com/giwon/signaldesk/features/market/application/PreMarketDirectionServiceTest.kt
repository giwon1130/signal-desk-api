package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class PreMarketDirectionServiceTest {
    private val service = PreMarketDirectionService(mock(MarketEvidenceService::class.java), MarketSessionService())
    private val now = Instant.parse("2026-09-09T23:30:00Z")
    private val empty = MarketEvidenceAnalyzer(MarketSessionService()).analyze(MarketEvidenceInput(now, emptyList(), null, null))
    private fun report(score: Double = 1.0): MarketEvidenceReport = empty.copy(riskLevel = "NORMAL", factors =
        listOf("us_equity" to .15, "us_futures" to .10, "kr_proxy" to .10, "semiconductors" to .20, "fx" to .10, "kr_night" to .10)
            .map { (id, weight) -> EvidenceFactor(id, id, weight, score, 1.0, emptyList(), "") })

    @Test fun `fixed weights do not amplify missing inputs`() {
        val full = report()
        val partial = full.copy(factors = full.factors.map { if (it.id == "kr_night") it.copy(score = null, coverage = 0.0) else it })
        assertThat(service.computeSignal(full).score).isCloseTo(100.0, offset(.001))
        assertThat(service.computeSignal(partial).score).isCloseTo(86.6667, offset(.001))
        assertThat(service.computeSignal(partial).coverage).isCloseTo(.866667, offset(.001))
    }
    @Test fun `one semiconductor bucket cannot decide direction`() {
        assertThat(service.computeSignal(report().copy(factors = report().factors.filter { it.id == "semiconductors" })).bias).isNull()
    }
    @Test fun `cash and daily rates cannot vote in overnight direction`() {
        val extra = report().copy(factors = report().factors + EvidenceFactor("kr_cash", "", .1, -1.0, 1.0, emptyList(), ""))
        assertThat(service.computeSignal(extra)).isEqualTo(service.computeSignal(report()))
    }
    @Test fun `conflicting groups abstain instead of promising a flat opening`() {
        val conflicting = report().copy(factors = report().factors.map { if (it.id in setOf("us_equity", "us_futures")) it.copy(score = -1.0) else it })
        assertThat(service.computeSignal(conflicting).bias).isNull()
    }
    @Test fun `unknown risk is not a bullish opening call and proxies never occupy futures slot`() {
        val result = service.fromReport(report().copy(riskLevel = "UNKNOWN"), now.atZone(ZoneId.of("Asia/Seoul")))
        assertThat(result.bias).isNull()
        assertThat(result.status).isEqualTo("RISK_CAUTION")
        assertThat(result.kospiFutures).isNull()
        assertThat(result.nightFutures).isNull()
        assertThat(result.warnings.joinToString()).contains("한국 야간선물이 아닙니다")
    }
    @Test fun `expired snapshot and market open are rejected`() {
        assertThat(service.fromReport(report(), now.plusSeconds(121).atZone(ZoneId.of("Asia/Seoul"))).status).isEqualTo("INSUFFICIENT")
        assertThat(service.fromReport(report(), now.plusSeconds(1800).atZone(ZoneId.of("Asia/Seoul"))).status).isEqualTo("OUTSIDE_WINDOW")
    }
    @Test fun `Korean holiday and weekend do not expose forecast`() {
        val zone = ZoneId.of("Asia/Seoul")
        assertThat(service.isPredictionWindow(ZonedDateTime.of(2026, 7, 15, 6, 30, 0, 0, zone))).isTrue()
        for (date in listOf("2026-10-05", "2026-10-09", "2026-07-18")) {
            assertThat(service.isPredictionWindow(ZonedDateTime.parse(date + "T08:30:00+09:00[Asia/Seoul]"))).isFalse()
        }
    }
}
