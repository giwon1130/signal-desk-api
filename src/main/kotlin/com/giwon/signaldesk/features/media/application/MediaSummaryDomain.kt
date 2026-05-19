package com.giwon.signaldesk.features.media.application

import java.time.Instant

enum class MediaSentiment { BULLISH, BEARISH, NEUTRAL }

/**
 * media_summaries.source. YOUTUBE 는 폐기됐지만 과거 row 호환을 위해 enum 에 남겨둔다.
 */
enum class MediaSource { YOUTUBE, NEWS_DIGEST, MORNING_BRIEF }

/** AI 분석 결과만 담는 순수 값 객체. 영상 메타데이터와 분리해서 합성. */
data class MediaSummaryAnalysis(
    val summary: String,
    val flowAnalysis: String,
    val keyTickers: List<String>,
    val sentiment: MediaSentiment,
)

/**
 * DB에 저장/조회하는 최종 형태.
 *  - NEWS_DIGEST: videoId="news-YYYY-MM-DD-KR" 같은 가상값, videoUrl=빈 문자열.
 *  - MORNING_BRIEF: videoId="brief-YYYY-MM-DD", channelTitle="모닝 브리프".
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
