package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.*

class NewsDigestMarketTest {
    @Test fun `US news digest requests US analysis and uses formal evidence bounded language`() {
        val now = Instant.parse("2026-10-09T21:30:00Z")
        val news = mock(GoogleNewsRssClient::class.java)
        `when`(news.fetchMarketNews()).thenReturn(listOf(MarketNews("US", "미국 기업 실적 발표", "TEST", "https://example.com/news", "", now.minusSeconds(600).toString())))
        val briefing = mock(EvidenceBriefingService::class.java)
        `when`(briefing.current("US")).thenReturn(MarketInsightAnalysis("미국 마감", "미국 지수 결과입니다.", MediaSentiment.BEARISH, emptyList()))
        val repo = object : MediaSummaryRepository {
            override fun findRecent(limit: Int) = emptyList<MediaSummary>()
            override fun findRecentBySource(source: MediaSource, limit: Int) = emptyList<MediaSummary>()
            override fun findById(id: String): MediaSummary? = null
            override fun findByVideoId(videoId: String): MediaSummary? = null
            override fun save(summary: MediaSummary) = summary
            override fun deleteByVideoId(videoId: String) {}
        }
        val service = NewsDigestService(news, briefing, MarketEvidenceAnalyzer(MarketSessionService()), repo, Clock.fixed(now, ZoneId.of("Asia/Seoul")))
        val result = service.runDigest("US")!!
        verify(briefing).current("US")
        verify(briefing, never()).current("KR")
        assertThat(result.summary).isEqualTo("미국 마감\n\n미국 지수 결과입니다.")
        assertThat(result.flowAnalysis).contains("원인으로 확정된 목록은 아닙니다", "https://example.com/news")
    }
}
