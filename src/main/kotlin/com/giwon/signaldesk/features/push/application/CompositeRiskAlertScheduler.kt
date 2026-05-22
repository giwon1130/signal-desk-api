package com.giwon.signaldesk.features.push.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class CompositeRiskAlertScheduler(
    private val compositeRiskAlertService: CompositeRiskAlertService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 08:32 KST 평일 — 모닝브리프(08:30) 직후. 한국 휴장일은 코드 가드. */
    @Scheduled(cron = "0 32 8 * * MON-FRI", zone = "Asia/Seoul")
    fun runDaily() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        if (!marketSessionService.isKrTradingDay(today)) {
            log.debug("CompositeRisk alert skipped — non-trading day {}", today)
            return
        }
        runCatching { compositeRiskAlertService.scanAndNotify() }
            .onFailure { log.error("CompositeRisk alert scheduler failed", it) }
    }
}
