package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushDevice
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One evidence pipeline for every scheduled market brief. Gemini availability never gates publication. */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class BriefPipeline(
    private val briefing: EvidenceBriefingService,
    private val repository: MediaSummaryRepository,
    private val expoPushClient: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val titleFmt = DateTimeFormatter.ofPattern("M월 d일")

    data class SlotConfig(
        val logLabel: String, val videoPrefix: String, val channelId: String,
        val channelTitle: String, val titleSuffix: String, val source: MediaSource,
    )

    fun <C> run(
        config: SlotConfig,
        today: LocalDate,
        force: Boolean,
        prepare: () -> C,
        transcriptLength: (C) -> Int = { 0 },
        keyTickers: (C) -> List<String> = { emptyList() },
        dispatchPush: (C, MarketInsightAnalysis) -> Unit,
    ): MediaSummary? {
        val videoId = "${config.videoPrefix}-${today.format(dateFmt)}"
        if (!force && repository.findByVideoId(videoId) != null) return null
        val context = prepare()
        val analysis = briefing.current()
        val saved = repository.save(buildSummary(
            videoId = videoId, channelId = config.channelId, channelTitle = config.channelTitle,
            videoTitle = "${today.format(titleFmt)} ${config.titleSuffix}",
            transcriptLength = transcriptLength(context), keyTickers = keyTickers(context),
            source = config.source, analysis = analysis,
        ))
        log.info("{} saved evidence brief videoId={}", config.logLabel, videoId)
        dispatchPush(context, analysis)
        return saved
    }

    /** 공통 MediaSummary 조립 — id/시각/본문(headline+summary, keyPoints 불릿) 형식은 모든 브리프 동일. */
    fun buildSummary(
        videoId: String,
        channelId: String,
        channelTitle: String,
        videoTitle: String,
        transcriptLength: Int,
        keyTickers: List<String>,
        source: MediaSource,
        analysis: MarketInsightAnalysis,
    ): MediaSummary = MediaSummary(
        id = UUID.randomUUID().toString(),
        channelId = channelId,
        channelTitle = channelTitle,
        videoId = videoId,
        videoTitle = videoTitle,
        videoUrl = "",
        publishedAt = Instant.now(),
        transcriptLength = transcriptLength,
        summary = analysis.headline + "\n\n" + analysis.summary,
        flowAnalysis = analysis.keyPoints.joinToString("\n") { "• $it" },
        keyTickers = keyTickers,
        sentiment = analysis.sentiment,
        hasTranscript = true,
        source = source,
        createdAt = Instant.now(),
    )

    /** sentiment → 푸시 타이틀 이모지. */
    fun sentimentEmoji(sentiment: MediaSentiment): String = when (sentiment) {
        MediaSentiment.BULLISH -> "🟢"
        MediaSentiment.BEARISH -> "🔴"
        MediaSentiment.NEUTRAL -> "🟡"
    }

    /** 공통 푸시 (title, body) — headline 비면 fallbackTitle, body 는 180자 제한. */
    fun briefPushContent(analysis: MarketInsightAnalysis, fallbackTitle: String): Pair<String, String> {
        val title = "${sentimentEmoji(analysis.sentiment)} ${analysis.headline.ifBlank { fallbackTitle }}"
        return title to analysis.summary.take(180)
    }

    /**
     * 타깃 사용자 × 기기 메시지를 모두 모아 한 번에 발송 — Expo Push API 배치 전송.
     * @param content userId 별 (title, body) — 개인화 필요 없으면 동일 값 반환.
     * @return 발송 메시지 수 (호출부 로그용).
     */
    fun sendToTargets(
        targets: Map<UUID, List<PushDevice>>,
        pushType: String,
        content: (UUID) -> Pair<String, String>,
    ): Int {
        val messages = targets.flatMap { (userId, devices) ->
            val (title, body) = content(userId)
            devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf("type" to pushType),
                    userId = userId,
                )
            }
        }
        expoPushClient.send(messages)
        return messages.size
    }

}
