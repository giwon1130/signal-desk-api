package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StockMoveContextTest {
    private val now = Instant.parse("2026-10-06T02:00:00Z")
    private val target = MoverReasonTarget("KR", "005930", "삼성전자", 5.2)
    private val empty = MoveDisclosureResult("COLLECTED_ONLY")
    private val checks = listOf(MoveSourceCheck("뉴스", "SUCCESS"))
    private fun index(id: String, change: Double, date: String = "2026-10-06") =
        MetricEvidence(id, if (id == "^KS11") "코스피" else "코스닥", "Yahoo", "https://finance.yahoo.com", now.toString(), date, "OBSERVED", 100.0, change, "PERCENT_CHANGE", "")
    private fun report(evidence: List<MetricEvidence>) = MarketEvidenceReport(asOf = now.toString(), horizon = "", regime = "", riskLevel = "", coveragePercent = 100,
        balanceScore = null, headline = "", conclusion = "", factors = emptyList(), evidence = evidence, warnings = emptyList(), newsEvidence = emptyList())

    @Test fun `same direction is context not a causal claim`() {
        val r = StockMoveContextBuilder.build(target, now, empty, emptyList(), checks, report(listOf(index("^KS11", 1.0), index("^KQ11", 0.8))))
        assertThat(r.status).isEqualTo("MARKET_CONTEXT")
        assertThat(r.summary).contains("코스피 +1.00%", "같은 방향").doesNotContain("때문", "영향으로")
        assertThat(r.evidence.single().timing).isEqualTo("CO_MOVEMENT_NOT_CAUSE")
    }

    @Test fun `mixed missing stale previous day or future indices cannot explain current move`() {
        val cases = listOf(listOf(index("^KS11", 1.0)), listOf(index("^KS11", 1.0), index("^KQ11", -1.0)),
            listOf(index("^KS11", 1.0, "2026-10-05"), index("^KQ11", 1.0)),
            listOf(index("^KS11", 1.0).copy(status = "STALE"), index("^KQ11", 1.0)),
            listOf(index("^KS11", 1.0).copy(observedAt = now.plusSeconds(60).toString()), index("^KQ11", 1.0)))
        cases.forEach { indices ->
            assertThat(StockMoveContextBuilder.build(target, now, empty, emptyList(), checks, report(indices)).status).isEqualTo("NO_RECENT_CATALYST")
        }
    }

    @Test fun `invalid future old and credentialed filing links are excluded`() {
        val e = MoveEvidence("DISCLOSURE", "유상증자 결정", "DART", "https://dart.fss.or.kr/one", publishedDate = "2026-10-06")
        val bad = listOf(e.copy(publishedDate = "2026-10-07"), e.copy(publishedDate = "2026-09-01"), e.copy(url = "https://key:secret@dart.fss.or.kr/one"),
            e.copy(publishedAt = now.plusSeconds(30).toString()), e.copy(publishedDate = "not-a-date"))
        assertThat(StockMoveContextBuilder.build(target, now, MoveDisclosureResult("COLLECTED_ONLY", bad), emptyList(), checks, null).evidence).isEmpty()
    }

    @Test fun `termination cancellation and correction never become a positive buyback summary`() {
        for ((title, expected) in listOf("자기주식취득신탁계약해지" to "해지", "자기주식취득결정취소" to "취소", "[기재정정]자기주식취득" to "정정")) {
            val filing = MoveEvidence("DISCLOSURE", title, "DART", "https://dart.fss.or.kr/one", publishedDate = "2026-10-06")
            val summary = StockMoveContextBuilder.build(target, now, MoveDisclosureResult("COLLECTED_ONLY", listOf(filing)), emptyList(), checks, null).summary
            assertThat(summary).contains(expected).doesNotContain("매입 규모", "호재")
        }
    }

    @Test fun `news quote preserves denial and does not claim announcement preceded move`() {
        val news = MarketNews("KR", "삼성전자, 인수 계약 사실 아냐", "뉴스", "https://example.com/n", "", now.toString())
        val r = StockMoveContextBuilder.build(target, now, empty, listOf(news), checks, null)
        assertThat(r.summary).contains("사실 아냐").doesNotContain("원인은 확인되지", "호재", "영향으로")
        assertThat(r.evidence.single().timing).isEqualTo("RELATED_PERIOD")
    }
}
