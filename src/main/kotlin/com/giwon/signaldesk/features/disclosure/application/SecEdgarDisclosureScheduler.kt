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
     * SEC 공시는 ET 평일 06:00~20:59의 주요 제출 시간대만 15분마다 확인한다.
     * 다른 DB 작업과 :00/:15/:30/:45에 묶어 Neon 기동 횟수를 줄인다.
     */
    @Scheduled(cron = "0 */15 6-20 * * MON-FRI", zone = "America/New_York")
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
