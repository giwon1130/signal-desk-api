package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.media.application.MediaSentiment
import com.giwon.signaldesk.features.media.application.MediaSource
import com.giwon.signaldesk.features.media.application.MediaSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * FLOW_READING media_summary → 앱 응답 매핑 회귀 보호.
 * buildSummary 가 summary="headline\n\nnarrative", flowAnalysis="• a\n• b" 로 저장하는 규칙을 분리한다.
 */
class FlowReadingResponseTest {

    private fun summary() = MediaSummary(
        id = "id-1",
        channelId = "ai-flow",
        channelTitle = "시데 AI 시황",
        videoId = "flow-close-2026-06-15",
        videoTitle = "6월 15일 마감 시황 흐름",
        videoUrl = "",
        publishedAt = Instant.parse("2026-06-15T06:50:00Z"),
        transcriptLength = 10,
        summary = "반도체 주도 코스피 상방\n\n외국인 순매수가 반도체로 집중되며 지수를 끌어올렸습니다. 조선은 쉬어가는 흐름입니다.",
        flowAnalysis = "• 주도: 반도체 — 외국인 순매수 1위\n• 순환매 대기: 조선 — 실적 모멘텀 잔존\n• 지수 전망: 코스피 상방 우호",
        keyTickers = listOf("삼성전자", "SK하이닉스"),
        sentiment = MediaSentiment.BULLISH,
        hasTranscript = true,
        source = MediaSource.FLOW_READING,
        createdAt = Instant.parse("2026-06-15T06:50:00Z"),
    )

    @Test
    fun `headline 과 narrative 를 분리하고 불릿을 정리한다`() {
        val r = FlowReadingController.FlowReadingResponse.from(summary())
        assertThat(r.headline).isEqualTo("반도체 주도 코스피 상방")
        assertThat(r.narrative).startsWith("외국인 순매수가 반도체로")
        assertThat(r.flowPoints).hasSize(3)
        assertThat(r.flowPoints[0]).isEqualTo("주도: 반도체 — 외국인 순매수 1위")
        assertThat(r.sentiment).isEqualTo("BULLISH")
        assertThat(r.keyTickers).containsExactly("삼성전자", "SK하이닉스")
        assertThat(r.title).isEqualTo("6월 15일 마감 시황 흐름")
    }

    @Test
    fun `본문이 없으면 headline 을 narrative 로 폴백`() {
        val r = FlowReadingController.FlowReadingResponse.from(summary().copy(summary = "한 줄만 있음"))
        assertThat(r.headline).isEqualTo("한 줄만 있음")
        assertThat(r.narrative).isEqualTo("한 줄만 있음")
    }
}
