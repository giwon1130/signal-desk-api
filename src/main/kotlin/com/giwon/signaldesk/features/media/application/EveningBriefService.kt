package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.PushRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 미국장 마감 관측치를 같은 근거 기반 파이프라인으로 전달. Gemini 실패 시에도 저장. */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class EveningBriefService(
    private val pipeline: BriefPipeline,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    fun runBrief() {
        val devices = pushRepository.listAllDevicesGroupedByUser()
        val enabled = alertPreferenceService.loadEveningBriefEnabledUsers()
        val targets = devices.filterKeys { it in enabled }
        pipeline.run(
            config = BriefPipeline.SlotConfig("EveningBrief", "evening", "evening-brief",
                "미국장 마감 브리프", "미국장 마감 브리프", MediaSource.EVENING_BRIEF),
            today = LocalDate.now(clock), force = false, prepare = {},
            dispatchPush = { _, analysis ->
                if (targets.isNotEmpty()) {
                    val content = pipeline.briefPushContent(analysis, "미국장 마감 브리프")
                    pipeline.sendToTargets(targets, "EVENING_BRIEF") { content }
                }
            },
        )
    }
}
