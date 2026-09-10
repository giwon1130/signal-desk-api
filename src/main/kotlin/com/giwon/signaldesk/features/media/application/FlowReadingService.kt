package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 시황 흐름 리딩 — 검증 가능한 규칙 분석을 한국어로 전달.
 *
 * 소스는 유튜브가 아니라 시데가 이미 수집 중인 시장 데이터(섹터·수급·급등락·지수·뉴스)다.
 * 브리프와 동일한 [BriefPipeline] 골격을 재사용하고, 분석은 [EvidenceBriefingService]로 통일.
 * media_summaries(FLOW_READING)는 중복방지 원장, 실제 노출은 '🤖 시데 AI 시황' 리더의 글로 발행
 * (구독자 피드 + 푸시). 구독은 PRO 전용.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class FlowReadingService(
    private val pipeline: BriefPipeline,
    private val readingService: com.giwon.signaldesk.features.reading.application.ReadingService,
    private val repository: MediaSummaryRepository,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    enum class Slot(val videoPrefix: String, val titleSuffix: String) {
        PREOPEN("flow-pre", "장전 시황 흐름"),
        CLOSE("flow-close", "마감 시황 흐름"),
    }

    /** 단일 실행. force=true 면 같은 날 기존 리딩이 있어도 재생성. */
    fun runFlow(slot: Slot, force: Boolean = false): MediaSummary? {
        val today = LocalDate.now(clock)
        return pipeline.run(
            config = BriefPipeline.SlotConfig(
                logLabel = "FlowReading($slot)",
                videoPrefix = slot.videoPrefix,
                channelId = "ai-flow",
                channelTitle = "시데 AI 시황",
                titleSuffix = slot.titleSuffix,
                source = MediaSource.FLOW_READING,
            ),
            today = today,
            force = force,
            prepare = {},
            dispatchPush = { _, _ -> },  // 푸시는 리더 글 발행 단계에서 처리
        )?.also { publishAsLeaderPost(it) }
    }

    /** 새 시황이 생성되면 '🤖 시데 AI 시황' 리더의 글로 발행 → 구독자 피드에 노출(적중률 인프라 재사용). */
    private fun publishAsLeaderPost(m: MediaSummary) {
        val title = m.summary.substringBefore("\n\n").trim().ifBlank { m.videoTitle }
        val narrative = m.summary.substringAfter("\n\n", "").trim()
        val body = listOf(narrative, m.flowAnalysis).filter { it.isNotBlank() }.joinToString("\n\n")
        runCatching {
            readingService.publishPost(
                com.giwon.signaldesk.features.reading.domain.AiLeaders.FLOW,
                title = title, body = body,
                visibility = com.giwon.signaldesk.features.reading.domain.PostVisibility.FOLLOWERS,
                confirmedCalls = emptyList(),
            )
        }.onFailure {
            // 발행 실패 → 중복방지 원장 롤백(다음 스케줄 재시도). 안 그러면 그날 콘텐츠 영구 유실.
            log.warn("FlowReading publishPost failed — 원장 롤백 videoId={}", m.videoId, it)
            runCatching { repository.deleteByVideoId(m.videoId) }
        }
    }

}
