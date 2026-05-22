package com.giwon.signaldesk.features.push.application

import com.giwon.signaldesk.features.market.application.MarketOverviewService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * 합성 위험도(Composite Risk) 푸시 알림.
 *
 * 모닝브리프 시간대(스케줄러가 08:32 KST 에 호출)에 합성 위험도를 확인하고,
 * score 가 임계치 이상이면 위험도 알림을 켠 사용자에게 전용 푸시를 한 번 보낸다.
 * 평온한 장에는 발송하지 않으므로 dedup 테이블 없이 1일 1회 스케줄 발화로 충분하다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class CompositeRiskAlertService(
    private val marketOverviewService: MarketOverviewService,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val expoPushClient: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scanAndNotify() {
        val risk = marketOverviewService.getSummary().compositeRisk
        if (risk.score < RISK_THRESHOLD) {
            log.info("CompositeRisk alert skipped — score {} < threshold {}", risk.score, RISK_THRESHOLD)
            return
        }

        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return

        val enabledUsers = alertPreferenceService.loadCompositeRiskEnabledUsers()
        val targets = devicesByUser.filterKeys { it in enabledUsers }
        if (targets.isEmpty()) {
            log.info("CompositeRisk alert — score {} but no opted-in recipients", risk.score)
            return
        }

        val title = "⚠️ 시장 위험도 ${risk.score}/10 — ${risk.level}"
        val body = risk.headline.take(180)
        val messages = targets.flatMap { (_, devices) ->
            devices.map { device ->
                ExpoPushClient.Message(
                    to = device.expoToken,
                    title = title,
                    body = body,
                    data = mapOf(
                        "type" to "COMPOSITE_RISK",
                        "score" to risk.score,
                        "level" to risk.level,
                    ),
                )
            }
        }
        expoPushClient.send(messages)
        log.info(
            "CompositeRisk alert dispatched. score={}, level={}, recipients={}, messages={}",
            risk.score, risk.level, targets.size, messages.size,
        )
    }

    companion object {
        /** 1~10 위험도 중 이 값 이상이면 푸시 (경계~고위험 구간). */
        const val RISK_THRESHOLD = 8
    }
}
