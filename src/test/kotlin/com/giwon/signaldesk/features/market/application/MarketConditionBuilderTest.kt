package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MarketConditionBuilderTest {
    private val sessions = MarketSessionService()
    private val builder = MarketConditionBuilder(sessions)
    private fun report(kospi: Double?, kosdaq: Double?, at: String = "2026-10-06T05:00:00Z"): MarketEvidenceReport {
        val now = Instant.parse(at)
        val quotes = listOf("^KS11" to kospi, "^KQ11" to kosdaq).mapNotNull { (id, change) ->
            change?.let { GlobalIndex(id, 100.0, it, id, at, "Yahoo:$id", 100.0 / (1 + it / 100),
                "2026-10-06", "2026-10-02") }
        }
        return MarketEvidenceAnalyzer(sessions).analyze(MarketEvidenceInput(now, quotes, null, null))
    }
    @Test fun `opposite local indices cannot be summarized as broad strength`() {
        val r = report(-1.2, 2.49)
        val view = builder.build(r, "KR")
        assertThat(view.direction).isEqualTo("MIXED")
        assertThat(view.headline).contains("엇갈리고 있습니다")
        assertThat(view.riskLevel).isEqualTo("UNKNOWN")
        assertThat(view.dataStatus).isEqualTo("PARTIAL")
        assertThat(r.factors.single { it.id == "kr_cash" }.score).isZero()
        assertThat(view.horizon).isEqualTo("CURRENT_SESSION")
    }
    @Test fun `missing primary index cannot imply neutral or calm`() {
        val view = builder.build(report(1.0, null), "KR")
        assertThat(view.direction).isEqualTo("UNKNOWN")
        assertThat(view.dataStatus).isEqualTo("INSUFFICIENT")
        assertThat(view.summary).contains("안정 상태를 뜻하지 않습니다")
    }
    @Test fun `missing VIX never generates compatibility fear or US heat scores`() {
        val metrics = builder.metrics(report(1.0, 1.0))
        assertThat(metrics.map { it.label }).containsExactly("KR Heat")
        assertThat(metrics.single().polarity).isEqualTo("SUPPORTIVE")
    }
    @Test fun `market direction and external risk are independent`() {
        val view = builder.build(report(1.0, 1.0).copy(riskLevel = "HIGH"), "KR")
        assertThat(view.direction).isEqualTo("UP")
        assertThat(view.riskLevel).isEqualTo("HIGH")
        assertThat(view.watchPoints.first()).contains("위험 신호")
    }
    @Test fun `after hours data is not presented as next opening forecast or final closing price`() {
        val view = builder.build(report(1.0, 1.0, "2026-10-06T07:00:00Z"), "KR")
        assertThat(view.horizon).isEqualTo("LATEST_OBSERVATION")
        assertThat(view.summary).contains("관측된 자료", "다음 개장 방향을 뜻하지 않습니다").doesNotContain("마감한")
    }
    @Test fun `stale timestamps are excluded from both summary and evidence factors`() {
        val view = builder.build(report(1.0, 1.0).copy(evidence = report(1.0, 1.0).evidence.map { it.copy(status = "STALE") }), "KR")
        assertThat(view.direction).isEqualTo("UNKNOWN")
        assertThat(view.evidence.all { it.status == "STALE" }).isTrue()
    }
}
