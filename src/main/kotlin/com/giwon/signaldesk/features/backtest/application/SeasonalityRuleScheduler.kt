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
 * 매일 08:00 KST 실행 — 트리거 날짜는 1년에 한 번만 맞으므로 자연히 월 1회 발송(별도 dedup 불필요).
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
        val due = mutableListOf<DueRule>()
        val plus2 = today.plusDays(2)
        if (plus2.dayOfMonth == 1) due += ruleService.findDue("BUY_MONTH", plus2.monthValue)
        if (today.dayOfMonth == 1) due += ruleService.findDue("AVOID_MONTH", today.monthValue)
        if (due.isEmpty()) return

        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        val messages = due.flatMap { rule ->
            devicesByUser[rule.userId].orEmpty().map { d -> buildMessage(d.expoToken, rule) }
        }
        if (messages.isEmpty()) return
        runCatching { expoPushClient.send(messages) }
            .onSuccess { log.info("Seasonality alerts dispatched. dueRules={}, messages={}", due.size, messages.size) }
            .onFailure { log.warn("Seasonality alert send failed", it) }
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
        )
    }
}
