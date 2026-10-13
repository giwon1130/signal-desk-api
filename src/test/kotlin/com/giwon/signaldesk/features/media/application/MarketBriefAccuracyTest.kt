package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.features.market.application.*
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.*

class MarketBriefAccuracyTest {
    private val sessions = MarketSessionService()
    private val mapper = jacksonObjectMapper()
    private val analyzer = MarketEvidenceAnalyzer(sessions)
    private val narrator = EvidenceNarrator(GeminiClient(mapper, "", "", "http://unused", "test"), mapper)
    private val pipeline = BriefPipeline(mock(EvidenceBriefingService::class.java), mock(MediaSummaryRepository::class.java), mock(ExpoPushClient::class.java))
    private fun quote(id: String, change: Double, at: String, date: String = at.take(10), previous: String = "2026-10-08") =
        GlobalIndex(id, 100.0, change, id, at, "Yahoo:$id", 100.0 / (1 + change / 100), date, previous)
    private fun report(a: Double, b: Double, at: String = "2026-10-12T06:30:00Z", now: String = "2026-10-12T06:40:00Z", market: String = "KR"): MarketEvidenceReport {
        val ids = if (market == "KR") listOf("^KS11", "^KQ11") else listOf("^GSPC", "^IXIC")
        return analyzer.analyze(MarketEvidenceInput(Instant.parse(now), listOf(quote(ids[0], a, at), quote(ids[1], b, at)), null, null, market = market))
    }

    @Test fun `October 12 crash stays bearish in card narration and push despite mixed reference factors`() {
        val assessed = report(-3.65, -4.83).copy(regime = "MIXED", riskLevel = "NORMAL",
            factors = listOf(EvidenceFactor("fx", "환율", .1, 1.0, 1.0, listOf("KRW=X"), "우호적")))
        val result = narrator.narrate(assessed)
        assertThat(result.headline).contains("큰 폭으로 하락 마감").doesNotContain("엇갈")
        assertThat(result.summary).contains("-3.65%", "-4.83%", "2026-10-12").doesNotContain("과도한 불안", "안정적")
        assertThat(result.sentiment).isEqualTo(MediaSentiment.BEARISH)
        assertThat(MarketConditionBuilder(sessions).build(assessed, "KR").direction).isEqualTo("DOWN")
        val push = pipeline.briefPushContent(result, "마감 브리프")
        assertThat(push.first).isEqualTo(result.headline)
        assertThat(push.second).contains("-3.65%", "-4.83%").doesNotContain("엇갈")
        assertThat(push.second.length).isLessThanOrEqualTo(180)
    }

    @Test fun `index result is still available when external coverage is insufficient`() {
        val assessed = report(-3.65, -4.83)
        assertThat(assessed.regime).isEqualTo("INSUFFICIENT_DATA")
        val result = narrator.narrate(assessed)
        assertThat(result.summary).contains("-3.65%", "참고 자료는 부족")
        assertThat(result.sentiment).isEqualTo(MediaSentiment.BEARISH)
    }

    @Test fun `October 13 single index crash is explicit and reported background reaches summary details and push`() {
        val input = MarketEvidenceInput(Instant.parse("2026-10-13T06:40:00Z"),
            listOf(quote("^KS11", -5.46, "2026-10-13T06:39:00Z", previous = "2026-10-12"),
                quote("^KQ11", -1.43, "2026-10-13T06:39:00Z", previous = "2026-10-12")), null,
            listOf(MarketNews("KR", "코스피 외국인 매도에 급락", "TEST", "https://example.com/news", "", "2026-10-13T06:20:00Z")))
        val result = narrator.narrate(analyzer.analyze(input))
        assertThat(result.headline).contains("코스피 5.46% 급락", "코스닥 1.43% 하락 마감")
        assertThat(result.summary).contains("-5.46%", "보도에서는 외국인 매도", "살펴볼 필요").doesNotContain("다음 거래일 방향")
        assertThat(result.keyPoints.joinToString()).contains("https://example.com/news", "기사 제목", "원인", "확정하지")
        val push = pipeline.briefPushContent(result, "마감")
        assertThat(push.second).contains("-5.46%", "보도에서 짚은 배경: 외국인 매도")
        assertThat(push.second.length).isLessThanOrEqualTo(180)
    }

    @Test fun `standout rally and opposite index crash retain each index direction`() {
        assertThat(report(4.2, 1.0).headline).contains("코스피 4.20% 급등", "코스닥 1.00% 상승")
        val mixed = report(-5.0, .5)
        assertThat(mixed.headline).contains("코스피 5.00% 급락", "코스닥 0.50% 상승")
        assertThat(mixed.marketObservation?.direction).isEqualTo("MIXED")
    }

    @Test fun `sharp rally cannot become bearish because of external risk`() {
        val result = narrator.narrate(report(3.0, 4.0).copy(regime = "RISK_CAUTION", riskLevel = "HIGH"))
        assertThat(result.headline).contains("큰 폭으로 상승")
        assertThat(result.sentiment).isEqualTo(MediaSentiment.BULLISH)
        assertThat(result.summary).contains("위험 지표", "+3.00%", "+4.00%")
    }

    @Test fun `mixed and flat cash sessions are distinguished from a mixed reference score`() {
        assertThat(report(-3.0, 2.0).marketObservation?.direction).isEqualTo("MIXED")
        assertThat(report(.01, -.02).marketObservation?.direction).isEqualTo("FLAT")
        assertThat(report(-.4, -.5).marketObservation?.direction).isEqualTo("DOWN")
    }

    @Test fun `1519 quote at 1540 is never labeled as a closing result`() {
        val result = narrator.narrate(report(-3.50, -4.76, at = "2026-10-12T06:19:50Z"))
        assertThat(result.assessment?.marketObservation?.status).isEqualTo("CLOSE_PENDING")
        assertThat(result.headline).contains("종가 확인 중").doesNotContain("마감했습니다")
        assertThat(result.summary).contains("15:19", "최종 종가는 아직 확인되지 않았습니다")
        assertThat(pipeline.briefPushContent(result, "마감").first).contains("종가 확인 중")
    }

    @Test fun `intraday snapshot never claims a close`() {
        val result = narrator.narrate(report(-2.5, -3.0, at = "2026-10-12T04:00:00Z", now = "2026-10-12T04:10:00Z"))
        assertThat(result.headline).contains("하락하고 있습니다").doesNotContain("마감")
        assertThat(result.summary).contains("13:00", "최종 마감 결과는 아닙니다")
    }

    @Test fun `morning starts with dated overseas results and never predicts today's Korean direction`() {
        val quotes = listOf(quote("^GSPC", -2.2, "2026-10-09T20:00:00Z"), quote("^IXIC", -3.2, "2026-10-09T20:00:00Z"))
        val r = analyzer.analyze(MarketEvidenceInput(Instant.parse("2026-10-11T23:30:00Z"), quotes, null, null))
        val result = narrator.narrate(r)
        assertThat(result.headline).startsWith("개장 전 점검 · 미국")
        assertThat(result.summary).contains("2026-10-09", "오늘 국내 증시의 상승·하락을 예고하는 것은 아닙니다")
        assertThat(pipeline.briefPushContent(result, "모닝").second).contains("개장 방향을 뜻하지 않습니다")
    }

    @Test fun `US close uses US observations in DST and winter`() {
        for ((at, now) in listOf("2026-10-12T20:00:00Z" to "2026-10-12T21:30:00Z", "2026-12-10T21:00:00Z" to "2026-12-10T21:30:00Z")) {
            val prior = LocalDate.parse(at.take(10)).minusDays(1).toString()
            val quotes = listOf(quote("^GSPC", -2.5, at, previous = prior), quote("^IXIC", -3.5, at, previous = prior))
            val r = analyzer.analyze(MarketEvidenceInput(Instant.parse(now), quotes, null, null, market = "US"))
            val result = narrator.narrate(r)
            assertThat(result.headline).startsWith("미국").contains("하락 마감")
            assertThat(result.summary).doesNotContain("국내")
        }
    }

    @Test fun `US early close uses 1300 New York not a hard coded 1600`() {
        val at = "2026-11-27T18:00:00Z"
        val q = listOf(quote("^GSPC", 1.0, at, previous = "2026-11-25"), quote("^IXIC", 1.2, at, previous = "2026-11-25"))
        val r = analyzer.analyze(MarketEvidenceInput(Instant.parse("2026-11-27T18:30:00Z"), q, null, null, market = "US"))
        assertThat(r.marketObservation?.status).isEqualTo("CLOSED")
    }

    @Test fun `missing index must not be replaced by supportive external factors`() {
        val r = analyzer.analyze(MarketEvidenceInput(Instant.parse("2026-10-12T06:40:00Z"), listOf(quote("^KS11", -3.5, "2026-10-12T06:30:00Z")), null, null))
        val result = narrator.narrate(r.copy(regime = "SUPPORTIVE"))
        assertThat(result.sentiment).isEqualTo(MediaSentiment.NEUTRAL)
        assertThat(result.headline).contains("자료가 부족")
        assertThat(result.summary).doesNotContain("우호적인 흐름이 우세")
    }
}
