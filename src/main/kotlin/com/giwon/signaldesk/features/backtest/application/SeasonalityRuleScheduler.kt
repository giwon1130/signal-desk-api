package com.giwon.signaldesk.features.backtest.application

import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * 저장된 시즌 규칙 → 해당 월이 다가오면 푸시.
 *  - BUY_MONTH: 그 달 시작 2일 전(강세 진입 리드타임)
 *  - AVOID_MONTH: 그 달 1일(회피 주의)
 * 매일 08:00 KST 실행. 트리거일 "정확히 일치" 방식은 그날 서버가 죽어 있으면 그 해 알림이
 * 통째로 사라지므로, 트리거일 이후 CATCH_UP_DAYS 의 만회 윈도우를 두고
 * last_notified_on 으로 같은 트리거 중복 발송을 막는다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class SeasonalityRuleScheduler(
    private val ruleService: SeasonalityRuleService,
    private val pushRepository: PushRepository,
    private val expoPushClient: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Seoul")
    fun notifyDueRules() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val due = ruleService.findAllEnabled().filter { isDue(it, today) }
        if (due.isEmpty()) return

        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        val messages = due.flatMap { rule ->
            devicesByUser[rule.userId].orEmpty().map { d -> buildMessage(d.expoToken, rule) }
        }
        if (messages.isNotEmpty()) {
            runCatching { expoPushClient.send(messages) }
                .onSuccess { log.info("Seasonality alerts dispatched. dueRules={}, messages={}", due.size, messages.size) }
                .onFailure { log.warn("Seasonality alert send failed", it) }
        }
        // 발송 기록은 디바이스 유무와 무관하게 남긴다 — 디바이스 없는 사용자는 어차피 못 받고,
        // 기록을 안 남기면 윈도우 내내 due 로 재계산만 반복한다.
        due.forEach { rule ->
            runCatching { ruleService.markNotified(rule.id, today) }
                .onFailure { log.warn("Seasonality markNotified failed — rule={}", rule.id, it) }
        }
    }

    /** 가장 최근 트리거일이 [트리거, 트리거+CATCH_UP_DAYS] 안이고 아직 그 트리거에 발송 안 했으면 due. */
    private fun isDue(r: DueRule, today: LocalDate): Boolean {
        val lead = if (r.kind == "BUY_MONTH") 2L else 0L
        // 작년~내년 후보 중 오늘 이전(포함)의 가장 최근 트리거 (1월 BUY 트리거 = 전년 12/30 케이스 포함)
        val trigger = (today.year - 1..today.year + 1)
            .map { y -> LocalDate.of(y, r.month, 1).minusDays(lead) }
            .filter { !it.isAfter(today) }
            .maxOrNull() ?: return false
        if (today.isAfter(trigger.plusDays(CATCH_UP_DAYS))) return false
        return r.lastNotifiedOn == null || r.lastNotifiedOn.isBefore(trigger)
    }

    private fun buildMessage(token: String, r: DueRule): ExpoPushClient.Message {
        val stat = buildList {
            r.meanPct?.let { add("평균 ${if (it >= 0) "+" else ""}${"%.1f".format(it)}%") }
            if (r.sampleYears != null && r.winRatePct != null) {
                val hit = Math.round(r.winRatePct / 100.0 * r.sampleYears)
                add("${r.sampleYears}년 중 ${hit}년 ${if (r.kind == "BUY_MONTH") "상승" else "하락"}")
            }
        }.joinToString(" · ")
        val (title, body) = if (r.kind == "BUY_MONTH") {
            "📈 ${r.name} ${r.month}월 강세 시즌" to "역사적으로 $stat. 진입 검토 구간입니다. (참고용 — 미래 보장 아님)"
        } else {
            "📉 ${r.name} ${r.month}월 약세 — 회피·주의" to "역사적으로 $stat. 신규 진입은 신중하게. (참고용 — 미래 보장 아님)"
        }
        return ExpoPushClient.Message(
            to = token, title = title, body = body,
            data = mapOf("type" to "SEASONALITY", "market" to r.market, "ticker" to r.ticker, "month" to r.month, "kind" to r.kind),
            userId = r.userId, // 방해금지 게이트 적용
        )
    }

    companion object {
        private const val CATCH_UP_DAYS = 3L
    }
}
