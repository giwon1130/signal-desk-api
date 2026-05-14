package com.giwon.signaldesk.features.media.application

import java.time.Instant

enum class MediaSentiment { BULLISH, BEARISH, NEUTRAL }

data class YouTubeVideo(
    val videoId: String,
    val channelId: String,
    val channelTitle: String,
    val title: String,
    val url: String,
    val publishedAt: Instant,
)

/** AI 분석 결과만 담는 순수 값 객체. 영상 메타데이터와 분리해서 합성. */
data class MediaSummaryAnalysis(
    val summary: String,
    val flowAnalysis: String,
    val keyTickers: List<String>,
    val sentiment: MediaSentiment,
)

/** DB에 저장/조회하는 최종 형태. */
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
    val createdAt: Instant,
)
