package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * KR 장중(12:30)·마감(15:40) 브리프 — 모닝 브리프와 같은 데이터 파이프라인을 KR 관점으로 재해석.
 *
 * 모닝 브리프와 차이:
 *  - 보유 공시 매칭 / 실적 캘린더 없음.
 *  - slot 에 따라 프롬프트·source·제목만 달라짐.
 *
 * 공통 골격(게이트/중복 체크/병렬 수집/저장/푸시)은 [BriefPipeline].
 * 앱은 /api/v1/media/summaries/latest 로 최신 브리프를 보여주므로, 시간대별로 가장 최근 브리프가 노출된다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class IntradayBriefService(
    private val pipeline: BriefPipeline,
    private val geminiClient: GeminiClient,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    enum class Slot(
        val source: MediaSource,
        val channelTitle: String,
        val videoPrefix: String,
        val titleSuffix: String,
        val prefColumn: String,   // alert_preferences 토글 컬럼
        val pushType: String,     // 푸시 data.type
        val defaultOn: Boolean,   // 미설정 사용자 기본 알림 여부
    ) {
        MIDDAY(MediaSource.MIDDAY_BRIEF, "장중 브리프", "midday", "장중 브리프", "midday_brief_enabled", "MIDDAY_BRIEF", false),
        CLOSE(MediaSource.CLOSE_BRIEF, "마감 브리프", "close", "마감 브리프", "close_brief_enabled", "CLOSE_BRIEF", true),
    }

    /** 단일 실행. force=true 면 기존 brief 가 있어도 재생성. */
    fun runBrief(slot: Slot, force: Boolean = false): MediaSummary? {
        val today = LocalDate.now(clock)
        return pipeline.run(
            config = BriefPipeline.SlotConfig(
                logLabel = "IntradayBrief($slot)",
                videoPrefix = slot.videoPrefix,
                channelId = slot.videoPrefix + "-brief",
                channelTitle = slot.channelTitle,
                titleSuffix = slot.titleSuffix,
                source = slot.source,
            ),
            today = today,
            force = force,
            prepare = {},
            analyze = { _, d ->
                geminiClient.summarizeIntradayBrief(
                    slot = slot.name,
                    vix = d.vix, indices = d.indices, macro = d.macro, headlines = d.headlines,
                    investorFlow = d.investorFlow, upcomingEvents = d.upcomingEvents,
                    krMarket = d.krMarket, krGainers = d.krGainers, krLosers = d.krLosers, global = d.global,
                )
            },
            transcriptLength = { _, d -> d.headlines.size },
            dispatchPush = { _, analysis ->
                runCatching { dispatchPush(slot, analysis) }
                    .onFailure { log.warn("IntradayBrief({}) push failed", slot, it) }
            },
        )
    }

    /** slot 토글 ON 사용자에게만 브리프 푸시 (기본 OFF — 켠 사람만). */
    private fun dispatchPush(slot: Slot, analysis: MarketInsightAnalysis) {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return
        val enabledUsers = alertPreferenceService.loadIntradayBriefEnabledUsers(slot.prefColumn, slot.defaultOn)
        val targets = devicesByUser.filterKeys { it in enabledUsers }
        if (targets.isEmpty()) return

        val content = pipeline.briefPushContent(analysis, fallbackTitle = slot.titleSuffix)
        val sent = pipeline.sendToTargets(targets, pushType = slot.pushType) { content }
        log.info("IntradayBrief({}) push dispatched. recipients={}, messages={}", slot, targets.size, sent)
    }
}
