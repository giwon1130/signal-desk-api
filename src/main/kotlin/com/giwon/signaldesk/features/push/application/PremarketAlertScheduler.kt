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
class PremarketAlertScheduler(
    private val premarketAlertService: PremarketAlertService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 한국장 프리마켓 — 08:00 KST 1차
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    fun runAtEight() = dispatch("08:00")

    // 한국장 프리마켓 — 08:30 KST 2차
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    fun runAtEightThirty() = dispatch("08:30")

    private fun dispatch(label: String) {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        if (!marketSessionService.isKrTradingDay(today)) {
            log.debug("Premarket alert skipped — non-trading day {}", today)
            return
        }
        runCatching { premarketAlertService.runPremarketAlert(label) }
            .onFailure { log.error("Premarket alert dispatch failed at {}", label, it) }
    }
}
