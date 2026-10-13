package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MarketDriversTest {
    private val now = Instant.parse("2026-10-13T06:40:00Z")
    private val observation = MarketObservation("KR", "2026-10-13", "AFTER_CLOSE", "CLOSED", "DOWN", "하락", "하락 마감", "결과", now.toString(), listOf("^KS11", "^KQ11"))
    private fun news(title: String, at: String? = "2026-10-13T06:21:00Z", market: String = "KR", url: String = "https://example.com/article?id=1", source: String = "TEST") =
        MarketNews(market, title, source, url, "unused impact must not be trusted", at)

    @Test fun `October 13 foreign selling headline becomes attributed context without invented flows or AI concerns`() {
        val result = MarketDriverBuilder.build(observation, listOf(news("23만전자·140만닉스...외인들 짐 싸자 코스피 '검은 화요일'")), now)
        assertThat(result.map { it.id }).containsExactly("foreign_sell")
        assertThat(result.single().summary).contains("보도", "외국인 매도").doesNotContain("AI", "조원", "확정")
        assertThat(result.single().basis).isEqualTo("HEADLINE")
        assertThat(result.single().sources.single().publishedAt).isEqualTo("2026-10-13T06:21:00Z")
    }

    @Test fun `distinct directly reported backgrounds are capped and retain source links`() {
        val items = listOf(
            news("코스피, 외국인 순매도에 급락"),
            news("코스피 하락, 반도체 급락 부담", url = "https://example.com/article?id=2"),
            news("국내 증시 하락, AI 수요 우려 부각", url = "https://example.com/article?id=3"),
            news("국내 증시 하락, 금리 상승 부담", url = "https://example.com/article?id=4"),
        )
        val result = MarketDriverBuilder.build(observation, items, now)
        assertThat(result.map { it.id }).containsExactly("foreign_sell", "semiconductor_weak", "ai_concern")
        assertThat(result.flatMap { it.sources }.map { it.url }).hasSize(3)
        assertThat(result.all { it.kind == "REPORTED_CONTEXT" }).isTrue()
    }

    @Test fun `old future undated wrong market and unrelated stories cannot explain today's session`() {
        val title = "코스피 외국인 매도에 급락"
        for (item in listOf(
            news(title, at = "2026-10-12T06:21:00Z"), news(title, at = now.plusSeconds(1).toString()),
            news(title, at = null), news(title, market = "US"), news(title, source = ""),
            news(title, url = "javascript:alert(1)"), news(title, url = "https://user:pass@example.com/a"),
            news("외국인 관광객 감소에 반도체 전시회 우려"), news("삼성전자 반도체 급락"),
        )) assertThat(MarketDriverBuilder.build(observation, listOf(item), now)).describedAs(item.title).isEmpty()
    }

    @Test fun `forecasts corrections reversals and counter directional reports are not causal shortcuts`() {
        for (title in listOf(
            "외국인 순매도에도 코스피 상승", "외국인 매수에도 코스피 하락",
            "코스피 외국인 매도에 내일 급락 전망", "코스피 급락, 외국인 매도설 사실 아냐",
            "외국인 매도 아닌 기관 영향 코스피 급락", "코스피 반등, 반도체 하락",
            "코스피 급락 우려 해소, 외국인 매도 완화", "코스피 외국인 매도...지난주 급락 회고",
            "코스피 급락, 금리 상승할까?", "코스피 하락 원인이 AI 수요 우려라는 주장 부인",
        )) assertThat(MarketDriverBuilder.build(observation, listOf(news(title)), now)).describedAs(title).isEmpty()
    }

    @Test fun `one syndicated headline cannot manufacture multiple drivers or independent corroboration`() {
        val title = "코스피, 외국인 매도와 반도체 급락에 하락"
        val result = MarketDriverBuilder.build(observation, listOf(news(title),
            news(title, source = "ANOTHER", url = "https://another.example/news")), now)
        assertThat(result).hasSize(1)
        assertThat(result.single().sources).hasSize(1)
        assertThat(result.single().summary).doesNotContain("교차", "확인됐")
    }

    @Test fun `opposing investor flows and sector moves never borrow each others direction`() {
        assertThat(MarketDriverBuilder.build(observation, listOf(news("코스피 하락, 외국인 매수 기관 매도")), now).map { it.id })
            .containsExactly("institution_sell")
        assertThat(MarketDriverBuilder.build(observation, listOf(news("코스피 하락, 기관 매수 외국인 매도")), now).map { it.id })
            .containsExactly("foreign_sell")
        assertThat(MarketDriverBuilder.build(observation, listOf(news("반도체 상승 코스피 하락")), now)).isEmpty()
        assertThat(MarketDriverBuilder.build(observation.copy(direction = "UP"), listOf(news("반도체 하락 코스피 상승")), now)).isEmpty()
    }

    @Test fun `observed rally uses buying rather than selling and mixed sessions do not force one explanation`() {
        assertThat(MarketDriverBuilder.build(observation.copy(direction = "UP"),
            listOf(news("코스피 외국인 순매수에 상승")), now).single().id).isEqualTo("foreign_buy")
        for (direction in listOf("MIXED", "FLAT", "UNKNOWN"))
            assertThat(MarketDriverBuilder.build(observation.copy(direction = direction), listOf(news("코스피 외국인 매도에 급락")), now)).isEmpty()
    }

    @Test fun `US close and Korean preopen context follow New York trading date and source market`() {
        val us = observation.copy(market = "US", tradingDate = "2026-10-12")
        val morning = Instant.parse("2026-10-12T23:30:00Z")
        val items = listOf(news("Nasdaq falls as Treasury yields rise", at = "2026-10-12T20:20:00Z", market = "US"))
        assertThat(MarketDriverBuilder.build(us, items, morning).single().id).isEqualTo("rates_rise")
        assertThat(MarketDriverBuilder.build(observation, items, now)).isEmpty()
    }

    @Test fun `query article identifiers survive deduplication but trackers do not`() {
        assertThat(canonicalNewsUrl("https://example.com/news?id=1&utm_source=google")).isEqualTo("https://example.com/news?id=1")
        assertThat(canonicalNewsUrl("https://example.com/news?id=2")).isNotEqualTo(canonicalNewsUrl("https://example.com/news?id=1"))
    }

    @Test fun `same date futures and extended hours headlines do not explain the cash close`() {
        val us = observation.copy(market = "US", tradingDate = "2026-10-12")
        for (title in listOf("나스닥 선물, 반도체 매도세 속 하락", "Nasdaq futures fall as Treasury yields rise",
            "미국 증시 시간외 하락, 반도체 급락", "미국 증시 개장 전 하락, 반도체 약세")) {
            val item = news(title, at = "2026-10-13T00:25:00Z", market = "US")
            assertThat(MarketDriverBuilder.build(us, listOf(item), now)).describedAs(title).isEmpty()
        }
    }
}
