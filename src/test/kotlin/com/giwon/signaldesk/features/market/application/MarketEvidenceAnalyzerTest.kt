package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import java.time.Instant

class MarketEvidenceAnalyzerTest {
    private val analyzer = MarketEvidenceAnalyzer(MarketSessionService())
    private val now = Instant.parse("2026-09-09T23:30:00Z") // Thursday 08:30 KST; US last session Wednesday
    private fun q(id: String, rate: Double = 1.5, value: Double = 100.0, date: String = "2026-09-09", at: String = "2026-09-09T20:00:00Z") =
        GlobalIndex(id, value, rate, id, at, "Yahoo:$id", value / (1 + rate / 100), date, "2026-09-08")
    private fun rate(id: String, value: Double = 4.3, previous: Double = 4.2, date: String = "2026-09-09") =
        FredSeriesSnapshot(value, (value / previous - 1) * 100, listOf(previous, value), previous, date,
            "2026-09-08", source = "FRED:$id", frequency = "DAILY")
    private fun macro(value: Double = 4.2, previous: Double = 4.3) = MacroSnapshot(null, null, null,
        treasury2y = rate("DGS2", value, previous), treasury10y = rate("DGS10", value, previous), wti = null)
    private fun all(): List<GlobalIndex> = YahooQuoteClient.BRIEFING_INDICES.keys.map { id ->
        when (id) {
            "KRW=X" -> q(id, -.5, 1300.0)
            "CL=F" -> q(id, 0.0, 75.0)
            "^VIX" -> q(id, -1.0, 18.0)
            "^KS11", "^KQ11" -> q(id, at = "2026-09-09T06:30:00Z")
            "SMSN.IL" -> q(id, at = "2026-09-09T15:30:00Z")
            else -> q(id)
        }
    }
    private fun report(quotes: List<GlobalIndex> = all(), macro: MacroSnapshot? = macro(), news: List<MarketNews>? = listOf(news("주요 경제지표 발표 대기"))) =
        analyzer.analyze(MarketEvidenceInput(now, quotes, macro, news))
    private fun news(title: String, source: String = "출처 A", url: String = "https://example.com/a", at: String = "2026-09-09T22:00:00Z") =
        MarketNews("GLOBAL", title, source, url, "", at)

    @Test fun `no data is insufficient not neutral or safe`() {
        val r = report(emptyList(), null, null)
        assertThat(r.regime).isEqualTo("INSUFFICIENT_DATA")
        assertThat(r.riskLevel).isEqualTo("UNKNOWN")
        assertThat(r.coveragePercent).isZero()
        assertThat(r.balanceScore).isNull()
    }

    @Test fun `broad supportive inputs produce explanation not return prediction`() {
        val r = report()
        assertThat(r.regime).isEqualTo("SUPPORTIVE")
        assertThat(r.coveragePercent).isEqualTo(90)
        assertThat(r.evidence.single { it.id == "KR_NIGHT" }.status).isEqualTo("MISSING")
        assertThat(r.warnings.joinToString()).contains("매수/매도 지시가 아니야", "미연결")
    }

    @Test fun `single ADR never replaces a sector and missing weights are not redistributed`() {
        val r = report(listOf(q("SKHY", 30.0)), null)
        val sector = r.factors.single { it.id == "semiconductors" }
        assertThat(sector.coverage).isCloseTo(.2, offset(1e-6))
        assertThat(sector.score).isNull()
        assertThat(r.regime).isEqualTo("INSUFFICIENT_DATA")
    }

    @Test fun `duplicating correlated inputs does not increase score or coverage`() {
        assertThat(report(all() + all())).isEqualTo(report())
    }

    @Test fun `nonfinite missing timestamp future and stale quotes are excluded`() {
        val cases = listOf(q("SOXX").copy(value = Double.NaN), q("SOXX").copy(observedAt = null),
            q("SOXX").copy(observedAt = "2026-09-10T23:30:00Z"),
            q("SOXX", date = "2026-09-08", at = "2026-09-08T20:00:00Z").copy(previousObservationDate = "2026-09-07"))
        val statuses = cases.map { bad -> report(listOf(bad)).evidence.single { it.id == "SOXX" }.status }
        assertThat(statuses).containsExactly("INVALID", "UNDATED", "INVALID", "STALE")
    }

    @Test fun `intraday quote older than ninety minutes is stale even on same date`() {
        val r = analyzer.analyze(MarketEvidenceInput(Instant.parse("2026-09-09T19:00:00Z"),
            listOf(q("SOXX", at = "2026-09-09T15:00:00Z")), null, emptyList()))
        assertThat(r.evidence.single { it.id == "SOXX" }.status).isEqualTo("STALE")
    }

    @Test fun `Friday close remains valid on Monday preopen and US holiday is respected`() {
        assertThat(analyzer.expectedSession(Instant.parse("2026-09-07T23:30:00Z"), "US").toString()).isEqualTo("2026-09-04")
        assertThat(analyzer.expectedSession(Instant.parse("2026-09-06T23:30:00Z"), "KR").toString()).isEqualTo("2026-09-04")
    }

    @Test fun `yield change is basis points not relative percent`() {
        val r = report(macro = macro(4.3, 4.2))
        val rate = r.evidence.single { it.id == "DGS10" }
        assertThat(rate.change).isCloseTo(10.0, offset(1e-6))
        assertThat(rate.unit).isEqualTo("BASIS_POINTS")
        assertThat(rate.status).isEqualTo("DELAYED")
        assertThat(rate.detail).contains("+10.00bp", "실시간 아님")
    }

    @Test fun `old and monthly yields do not vote`() {
        val m = macro().copy(treasury10y = rate("DGS10", date = "2026-08-31"), treasury2y = rate("DGS2").copy(frequency = "MONTHLY"))
        val r = report(macro = m)
        assertThat(r.factors.single { it.id == "rates" }.score).isNull()
    }

    @Test fun `falling rates with falling equities are not treated as bullish`() {
        val r = report(all().map { if (it.symbol in setOf("^GSPC", "^IXIC")) q(it.symbol!!, -2.0) else it })
        assertThat(r.factors.single { it.id == "rates" }.score).isEqualTo(0.0)
        assertThat(r.warnings.joinToString()).contains("금리 하락을 호재로 가산하지 않았어")
    }

    @Test fun `conflicting groups are mixed despite positive arithmetic balance`() {
        val r = report(all().map { if (it.symbol in setOf("^GSPC", "^IXIC", "ES=F")) q(it.symbol!!, -1.5) else it })
        assertThat(r.regime).isEqualTo("MIXED")
    }

    @Test fun `war headlines alone do not prove a war or trigger high risk`() {
        val n = listOf(news("미사일 발사 보도", "A", "https://x.com/a"), news("해협 봉쇄 우려", "B", "https://x.com/b"), news("군사 공격 가능성", "C", "https://x.com/c"))
        val r = report(news = n)
        assertThat(r.riskLevel).isEqualTo("ELEVATED")
        assertThat(r.regime).isNotEqualTo("SUPPORTIVE")
        val corroborated = report(all().map { if (it.symbol == "CL=F") q("CL=F", 3.0, 80.0) else it }, news = n)
        assertThat(corroborated.riskLevel).isEqualTo("HIGH")
    }

    @Test fun `same story duplicated across queries is counted once`() {
        val a = news("미사일 발사 보도 - A")
        val b = news("미사일 발사 보도 - B", "B", "https://example.com/b")
        assertThat(analyzer.recentNews(listOf(a, a.copy(url = a.url + "?q=2"), b), now)).hasSize(1)
    }

    @Test fun `old future and undated news are rejected`() {
        assertThat(analyzer.recentNews(listOf(news("공습", at = "2026-09-07T01:00:00Z"),
            news("공습", at = "2026-09-10T01:00:00Z"), news("공습").copy(publishedAt = null)), now)).isEmpty()
    }

    @Test fun `empty recent news does not imply low geopolitical risk`() {
        assertThat(report(news = emptyList()).riskLevel).isEqualTo("UNKNOWN")
    }

    @Test fun `ceasefire denial and trade war are not escalation and broken ceasefire is`() {
        listOf("휴전 합의로 전쟁 긴장 완화", "정부 군사 공격 부인", "미중 무역전쟁", "No war planned")
            .forEach { assertThat(analyzer.isEscalation(it)).isFalse() }
        assertThat(analyzer.isEscalation("휴전 붕괴 이후 공습 재개" )).isTrue()
    }

    @Test fun `analysis is deterministic and replayable`() {
        val input = MarketEvidenceInput(now, all(), macro(), emptyList())
        assertThat(analyzer.analyze(input)).isEqualTo(analyzer.analyze(input))
    }
}
