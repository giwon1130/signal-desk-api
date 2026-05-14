package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 미디어(유튜브) 요약 오케스트레이션.
 *
 * 흐름:
 *   1) 설정된 채널 ID 각각에 대해 RSS 로 최신 영상 1개 조회
 *   2) 이미 처리된 video_id 면 skip
 *   3) 자막 fetch (없으면 fallback)
 *   4) Gemini 로 요약/흐름 분석
 *   5) DB 저장
 *
 * jdbc 저장소 모드에서만 활성화 (Repository 가 같은 조건).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MediaSummaryService(
    private val rssClient: YouTubeRssClient,
    private val transcriptClient: YouTubeTranscriptClient,
    private val geminiClient: GeminiClient,
    private val repository: MediaSummaryRepository,
    @Value("\${signal-desk.integrations.youtube.channel-ids:}") private val channelIdsRaw: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 스케줄러/수동 트리거 모두 이 메소드를 호출. 처리된 영상 수 반환. */
    fun runDailyScan(): Int {
        if (!geminiClient.isEnabled()) {
            log.warn("MediaSummaryService skipped — GEMINI_API_KEY 미설정")
            return 0
        }
        val channelIds = channelIdsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (channelIds.isEmpty()) {
            log.info("MediaSummaryService skipped — YOUTUBE_CHANNEL_IDS 미설정")
            return 0
        }

        var processed = 0
        channelIds.forEach { channelId ->
            runCatching { processChannel(channelId)?.let { processed++ } }
                .onFailure { log.warn("media summary failed for channel={}", channelId, it) }
        }
        log.info("MediaSummaryService scan done. channels={}, processed={}", channelIds.size, processed)
        return processed
    }

    private fun processChannel(channelId: String): MediaSummary? {
        val videos = rssClient.fetchLatestVideos(channelId)
        val latest = videos.firstOrNull() ?: return null
        if (repository.findByVideoId(latest.videoId) != null) {
            log.info("media summary already exists. videoId={}", latest.videoId)
            return null
        }
        return summarizeVideo(latest)
    }

    /** 외부에서 특정 영상만 강제 요약하고 싶을 때 사용 (수동 트리거 등). */
    fun summarizeVideo(video: YouTubeVideo): MediaSummary? {
        val transcript = transcriptClient.fetchTranscript(video.videoId)
        val analysis = geminiClient.summarize(video.title, transcript) ?: run {
            log.warn("Gemini analysis returned null. videoId={}", video.videoId)
            return null
        }
        val summary = MediaSummary(
            id = UUID.randomUUID().toString(),
            channelId = video.channelId,
            channelTitle = video.channelTitle,
            videoId = video.videoId,
            videoTitle = video.title,
            videoUrl = video.url,
            publishedAt = video.publishedAt,
            transcriptLength = transcript.length,
            summary = analysis.summary,
            flowAnalysis = analysis.flowAnalysis,
            keyTickers = analysis.keyTickers,
            sentiment = analysis.sentiment,
            hasTranscript = transcript.isNotBlank(),
            createdAt = Instant.now(),
        )
        return repository.save(summary)
    }
}
