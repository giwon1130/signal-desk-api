package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 유튜브 방송 → AI 흐름 요약. 채널 RSS 최신 VOD → Supadata 자막 → Gemini 요약 →
 * media_summaries(source=FLOW_READING, channelTitle=채널명, videoUrl=원문). 같은 AI 시황 룸에 노출.
 *
 * Supadata 키 없으면(=isEnabled false) 전부 no-op — 데이터 기반 흐름은 그대로 동작.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class YoutubeFlowReadingService(
    private val channelClient: YoutubeChannelClient,
    private val transcriptClient: SupadataTranscriptClient,
    private val geminiClient: GeminiClient,
    private val repository: MediaSummaryRepository,
    @Value("\${signal-desk.integrations.youtube.flow-channels:}") private val channelsRaw: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ChannelCfg(val channelId: String, val titleFilter: String?, val label: String)

    private val channels: List<ChannelCfg> by lazy {
        channelsRaw.split(",").mapNotNull { raw ->
            val parts = raw.split("|").map { it.trim() }
            val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChannelCfg(id, parts.getOrNull(1)?.takeIf { it.isNotBlank() }, parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "방송 요약")
        }
    }

    /** 설정된 모든 채널 1회 처리. 처리/저장된 건수 반환. */
    fun runAll(force: Boolean = false): Int {
        if (!transcriptClient.isEnabled() || !geminiClient.isEnabled() || channels.isEmpty()) return 0
        return channels.count { cfg ->
            runCatching { runChannel(cfg, force) != null }
                .getOrElse { e -> log.warn("YoutubeFlow({}) failed", cfg.label, e); false }
        }
    }

    fun runChannel(cfg: ChannelCfg, force: Boolean = false): MediaSummary? {
        val video = channelClient.latestVideo(cfg.channelId, cfg.titleFilter) ?: run {
            log.info("YoutubeFlow({}) no video", cfg.label); return null
        }
        val videoId = "yt-${video.videoId}"
        if (!force && repository.findByVideoId(videoId) != null) {
            log.info("YoutubeFlow({}) already summarized {}", cfg.label, video.videoId); return null
        }
        val transcript = transcriptClient.fetchTranscript(video.url) ?: run {
            log.info("YoutubeFlow({}) no transcript yet for {} ({})", cfg.label, video.videoId, video.title); return null
        }
        val analysis = runCatching { geminiClient.summarizeYoutubeFlow(cfg.label, video.title, transcript) }
            .getOrNull() ?: run { log.warn("YoutubeFlow({}) gemini null", cfg.label); return null }

        val saved = repository.save(
            MediaSummary(
                id = UUID.randomUUID().toString(),
                channelId = cfg.channelId,
                channelTitle = cfg.label,
                videoId = videoId,
                videoTitle = video.title.take(500),
                videoUrl = video.url,
                publishedAt = Instant.now(),
                transcriptLength = transcript.length,
                summary = analysis.headline + "\n\n" + analysis.summary,
                flowAnalysis = analysis.keyPoints.joinToString("\n") { "• $it" },
                keyTickers = emptyList(),
                sentiment = analysis.sentiment,
                hasTranscript = true,
                source = MediaSource.FLOW_READING,
                createdAt = Instant.now(),
            ),
        )
        log.info("YoutubeFlow({}) saved {} — {}", cfg.label, video.videoId, analysis.headline)
        return saved
    }
}
