package com.giwon.signaldesk.features.media.application

import java.time.Instant

enum class MediaSentiment { BULLISH, BEARISH, NEUTRAL }

/**
 * media_summaries.source. YOUTUBE 는 폐기됐지만 과거 row 호환을 위해 enum 에 남겨둔다.
 * MORNING_BRIEF: KR 장 시작 전 08:30 KST 종합 (야간 미국장 + 뉴스 + 보유 공시)
 * MIDDAY_BRIEF: KR 장중 12:30 KST 중간 점검 (오전장 흐름 + 수급 + 헤드라인)
 * CLOSE_BRIEF: KR 장 마감 후 15:40 KST 정리 (마감 흐름 + 수급 + 내일 관전)
 * EVENING_BRIEF: 미국 장 마감 직후 06:30 KST 종합 (NASDAQ/S&P + 주도주 + 실적 + 뉴스)
 */
enum class MediaSource { YOUTUBE, NEWS_DIGEST, MORNING_BRIEF, MIDDAY_BRIEF, CLOSE_BRIEF, EVENING_BRIEF }

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
