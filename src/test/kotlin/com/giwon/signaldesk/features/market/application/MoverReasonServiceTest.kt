package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MoverReasonServiceTest {
    private val news = mock(GoogleNewsRssClient::class.java)
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val service = MoverReasonService(mock(TopMoversService::class.java), news,
        mock(MarketSessionService::class.java), Clock.fixed(now, ZoneOffset.UTC))
    private val target = MoverReasonTarget("KR", "005930", "삼성전자", -6.0)

    @Test fun `news outage delivers unknown cause instead of a generated explanation`() {
        `when`(news.fetchByQuery("KR", "삼성전자 주가 when:1d")).thenThrow(IllegalStateException("offline"))
        assertThat(service.reasonsForTickers(listOf(target)))
            .containsEntry("KR" to "005930", MoverNewsEvidence.UNKNOWN_CAUSE)
    }

    @Test fun `a search result is checked for relevance before it reaches an alert`() {
        `when`(news.fetchByQuery("KR", "삼성전자 주가 when:1d")).thenReturn(listOf(
            MarketNews("KR", "SK하이닉스 실적 발표", "뉴스", "https://example.com/1", "", now.toString()),
        ))
        assertThat(service.reasonsForTickers(listOf(target))["KR" to "005930"])
            .isEqualTo(MoverNewsEvidence.UNKNOWN_CAUSE)
    }

    @Test fun `same ticker in different markets cannot share news`() {
        val targets = listOf(MoverReasonTarget("KR", "ABC", "한국회사", 5.0), MoverReasonTarget("US", "ABC", "US Company", -5.0))
        `when`(news.fetchByQuery("KR", "한국회사 주가 when:1d")).thenReturn(listOf(
            MarketNews("KR", "한국회사 실적 발표", "뉴스", "https://example.com/kr", "", now.toString())))
        `when`(news.fetchByQuery("US", "US Company stock when:1d")).thenReturn(emptyList())
        val result = service.reasonsForTickers(targets)
        assertThat(result["KR" to "ABC"]).contains("한국회사 실적 발표")
        assertThat(result["US" to "ABC"]).isEqualTo(MoverNewsEvidence.UNKNOWN_CAUSE)
    }
}
