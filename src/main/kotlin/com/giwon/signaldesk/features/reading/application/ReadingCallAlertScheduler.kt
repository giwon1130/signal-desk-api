package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * 리딩 콜 "거봐" 알림 스케줄러 — 장중 10분 주기로 목표 도달 콜 스캔.
 * WatchlistAlertScheduler 와 동일 가드(거래일/휴장).
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class ReadingCallAlertScheduler(
    private val alertService: ReadingCallAlertService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 */10 9-15 * * MON-FRI", zone = "Asia/Seoul")
    fun runKr() {
        if (!marketSessionService.isKrTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")))) return
        runCatching { alertService.scanAndNotify(marketFilter = "KR") }
            .onFailure { log.error("KR reading call alert scan failed", it) }
    }

    @Scheduled(cron = "0 */10 9-15 * * MON-FRI", zone = "America/New_York")
    fun runUs() {
        if (!marketSessionService.isUsTradingDay(LocalDate.now(ZoneId.of("America/New_York")))) return
        runCatching { alertService.scanAndNotify(marketFilter = "US") }
            .onFailure { log.error("US reading call alert scan failed", it) }
    }
}
