package com.giwon.signaldesk.features.media.application

import java.time.Instant

enum class MediaSentiment { BULLISH, BEARISH, NEUTRAL }

enum class MediaSource { YOUTUBE, NEWS_DIGEST, MORNING_BRIEF }

data class YouTubeVideo(
    val videoId: String,
    val channelId: String,
    val channelTitle: String,
    val title: String,
    val url: String,
    val publishedAt: Instant,
    val description: String = "",
)

/** AI 분석 결과만 담는 순수 값 객체. 영상 메타데이터와 분리해서 합성. */
data class MediaSummaryAnalysis(
    val summary: String,
    val flowAnalysis: String,
    val keyTickers: List<String>,
    val sentiment: MediaSentiment,
)

/**
 * DB에 저장/조회하는 최종 형태.
 *
 * YOUTUBE source: channelId/videoId/videoUrl 이 실제 유튜브 값.
 * NEWS_DIGEST source: 가상의 video_id (예: "news-2026-05-15-KR"),
 *                     channelTitle="시장 마감 종합", videoUrl=빈 문자열.
 */
data class MediaSummary(
    val id: String,
    val channelId: String,
    val channelTitle: String,
    val videoId: String,
    val videoTitle: String,
    val videoUrl: String,
    val publishedAt: Instant,
    val transcriptLength: Int,
    val summary: String,
    val flowAnalysis: String,
    val keyTickers: List<String>,
    val sentiment: MediaSentiment,
    val hasTranscript: Boolean,
    val source: MediaSource,
    val createdAt: Instant,
)
