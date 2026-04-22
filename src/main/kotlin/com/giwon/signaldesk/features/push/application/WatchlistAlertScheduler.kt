package com.giwon.signaldesk.features.push.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class WatchlistAlertScheduler(
    private val watchlistAlertService: WatchlistAlertService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 한국장 시간대(09:00~15:30 KST) 커버용: 5분 주기
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    fun run() {
        runCatching { watchlistAlertService.scanAndNotify() }
            .onFailure { log.error("Watchlist alert scan failed", it) }
    }
}
