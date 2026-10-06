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

    @Test fun `news outage is distinct from no matching evidence`() {
        `when`(news.searchWithStatus(anyString(), anyString())).thenThrow(IllegalStateException("offline"))
        assertThat(service.reasonsForTickers(listOf(target)))
            .containsEntry("KR" to "005930", StockMoveContextBuilder.UNAVAILABLE)
        assertThat(service.contextsForTickers(listOf(target)).values.single().status).isEqualTo("UNAVAILABLE")
    }

    @Test fun `a search result is checked for relevance before it reaches an alert`() {
        `when`(news.searchWithStatus(anyString(), anyString())).thenReturn(NewsSearchResult("SUCCESS", listOf(
            MarketNews("KR", "SK하이닉스 실적 발표", "뉴스", "https://example.com/1", "", now.toString()),
        )))
        assertThat(service.reasonsForTickers(listOf(target))["KR" to "005930"])
            .isEqualTo(MoverNewsEvidence.UNKNOWN_CAUSE)
    }

    @Test fun `same ticker in different markets cannot share news`() {
        val targets = listOf(MoverReasonTarget("KR", "ABC", "한국회사", 5.0), MoverReasonTarget("US", "ABC", "US Company", -5.0))
        `when`(news.searchWithStatus(anyString(), anyString())).thenAnswer { call ->
            if (call.getArgument<String>(0) == "KR") NewsSearchResult("SUCCESS", listOf(
                MarketNews("KR", "한국회사 실적 발표", "뉴스", "https://example.com/kr", "", now.toString())))
            else NewsSearchResult("SUCCESS")
        }
        val result = service.reasonsForTickers(targets)
        assertThat(result["KR" to "ABC"]).contains("한국회사 실적 발표")
        assertThat(result["US" to "ABC"]).isEqualTo(MoverNewsEvidence.UNKNOWN_CAUSE)
    }
    @Test fun `collected filings are reused even during news outage`() {
        `when`(news.searchWithStatus(anyString(), anyString())).thenReturn(NewsSearchResult("UNAVAILABLE"))
        val filing = MoveEvidence("DISCLOSURE", "단일판매 공급계약 체결", "DART", "https://dart.fss.or.kr/test", publishedDate = "2026-09-21")
        val svc = MoverReasonService(mock(TopMoversService::class.java), news, mock(MarketSessionService::class.java),
            Clock.fixed(now, ZoneOffset.UTC), MoverDisclosureSource { _, _ -> MoveDisclosureResult("COLLECTED_ONLY", listOf(filing)) })
        val result = svc.contextsForTickers(listOf(target)).values.single()
        assertThat(result.status).isEqualTo("EVIDENCE_FOUND")
        assertThat(result.summary).contains("공급계약").doesNotContain("때문", "영향으로")
        assertThat(result.notes).anyMatch { it.contains("접수 날짜만") }
    }

    @Test fun `deduped targets and consecutive users share five minute lookup cache`() {
        `when`(news.searchWithStatus(anyString(), anyString())).thenReturn(NewsSearchResult("SUCCESS"))
        service.contextsForTickers(listOf(target, target))
        service.contextsForTickers(listOf(target.copy(changeRate = 9.0)))
        verify(news, times(2)).searchWithStatus(anyString(), anyString())
    }

    @Test fun `failed lookup retries after a minute`() {
        var time = now
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = time
        }
        val svc = MoverReasonService(mock(TopMoversService::class.java), news, mock(MarketSessionService::class.java), clock)
        `when`(news.searchWithStatus(anyString(), anyString())).thenReturn(NewsSearchResult("UNAVAILABLE"))
        svc.contextsForTickers(listOf(target))
        time = now.plusSeconds(59)
        svc.contextsForTickers(listOf(target))
        verify(news, times(2)).searchWithStatus(anyString(), anyString())
        time = now.plusSeconds(61)
        svc.contextsForTickers(listOf(target))
        verify(news, times(4)).searchWithStatus(anyString(), anyString())
    }
}
