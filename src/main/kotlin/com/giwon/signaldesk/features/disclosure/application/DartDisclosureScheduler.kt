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
class DartDisclosureScheduler(
    private val service: DartDisclosureService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * OpenDART 공시는 평일 07:00 ~ 19:30 사이에 주로 접수됨 (장 외 시간에도 들어옴).
     * 5분 주기 × 거래일 07-19 시 → 일 ~156 호출. 일일 quota 10,000 대비 매우 안전.
     */
    @Scheduled(cron = "0 */5 7-19 * * MON-FRI", zone = "Asia/Seoul")
    fun runScan() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        if (!marketSessionService.isKrTradingDay(today)) {
            log.debug("dart disclosure scan skipped — non-trading day {}", today)
            return
        }
        runCatching { service.runScan() }
            .onFailure { log.error("dart disclosure scan failed", it) }
    }
}
