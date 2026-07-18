package com.giwon.signaldesk.features.disclosure.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class SecEdgarDisclosureScheduler(
    private val service: SecEdgarDisclosureService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * SEC 공시는 ET 평일 ~7am-8pm 사이가 피크지만 24시간 들어올 수 있다.
     * 5분 폴링 (KST 24시간) — SEC fair-access(10 req/sec) 한도 대비 매우 여유.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    fun runScan() {
        val today = LocalDate.now(ZoneId.of("America/New_York"))
        if (!marketSessionService.isUsTradingDay(today)) {
            log.debug("SEC EDGAR scan skipped — US non-trading day {}", today)
            return
        }
        runCatching { service.runScan() }
            .onFailure { log.error("SEC EDGAR scan failed", it) }
    }
}
