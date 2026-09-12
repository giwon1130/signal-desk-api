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

    /**
     * 공통 푸시 (title, body).
     * 앱 본문의 해설을 그대로 자르지 않고, 규칙 엔진의 결론을 짧은 행동 중심 알림으로 바꾼다.
     */
    fun briefPushContent(analysis: MarketInsightAnalysis, fallbackTitle: String): Pair<String, String> {
        val assessment = analysis.assessment
        if (assessment == null) {
            val title = "${sentimentEmoji(analysis.sentiment)} ${analysis.headline.ifBlank { fallbackTitle }}"
            return title to analysis.summary.take(180)
        }
        val titleText = when (assessment.regime) {
            "RISK_CAUTION" -> "위험 신호를 먼저 확인해 주세요"
            "PRESSURED" -> "시장 부담이 커지고 있습니다"
            "SUPPORTIVE" -> "우호적인 흐름이 나타나고 있습니다"
            "MIXED" -> "시장 방향이 엇갈리고 있습니다"
            else -> "최신 시황 자료를 확인 중입니다"
        }
        val driver = assessment.factors
            .filter { factor ->
                val score = factor.score ?: return@filter false
                kotlin.math.abs(score) >= .2 && when (assessment.regime) {
                    "RISK_CAUTION", "PRESSURED" -> score < 0
                    "SUPPORTIVE" -> score > 0
                    else -> true
                }
            }
            .sortedByDescending { kotlin.math.abs(it.score!! * it.weight) }
            .firstOrNull()
            ?.let(::pushDriverSentence)
        val guidance = when (assessment.regime) {
            "RISK_CAUTION", "PRESSURED" -> "신규 진입은 서두르기보다 흐름이 안정되는지 확인해 주세요."
            "SUPPORTIVE" -> "장중 가격과 수급이 이어지는지 확인한 뒤 대응해 주세요."
            "MIXED" -> "방향이 확인될 때까지 무리한 진입은 피해 주세요."
            else -> "일부 지표만으로 단정하지 말고 장 흐름을 확인해 주세요."
        }
        return "${sentimentEmoji(analysis.sentiment)} $titleText" to listOfNotNull(driver, guidance)
            .joinToString(" ").take(180)
    }

    private fun pushDriverSentence(factor: com.giwon.signaldesk.features.market.application.EvidenceFactor): String {
        val supportive = factor.score!! >= .2
        return when (factor.id) {
            "kr_cash" -> if (supportive) "국내 증시가 강한 흐름입니다." else "국내 증시가 약세입니다."
            "us_equity" -> if (supportive) "미국 증시 흐름이 우호적입니다." else "미국 증시가 약세입니다."
            "us_futures" -> if (supportive) "미국 선물이 강세입니다." else "미국 선물이 약세입니다."
            "kr_proxy" -> if (supportive) "한국 관련 해외 ETF가 강세입니다." else "한국 관련 해외 ETF가 약세입니다."
            "semiconductors" -> if (supportive) "반도체 관련 지표가 강세입니다." else "반도체 관련 지표가 약세입니다."
            "fx" -> if (supportive) "환율 부담이 완화되고 있습니다." else "환율 흐름이 부담스럽습니다."
            "rates" -> if (supportive) "미국 금리 부담이 완화되고 있습니다." else "미국 금리 흐름이 부담스럽습니다."
            "kr_night" -> if (supportive) "코스피200 야간선물이 강세입니다." else "코스피200 야간선물이 약세입니다."
            else -> if (supportive) "${factor.label} 흐름이 우호적입니다." else "${factor.label} 흐름이 부담스럽습니다."
        }
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
