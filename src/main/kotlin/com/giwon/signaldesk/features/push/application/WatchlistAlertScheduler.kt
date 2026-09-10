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
class WatchlistAlertScheduler(
    private val watchlistAlertService: WatchlistAlertService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 한국장 시간대(09:00~15:59 KST) 커버용: 15분 주기. 휴장일은 코드에서 가드한다.
    @Scheduled(cron = "0 */15 9-15 * * MON-FRI", zone = "Asia/Seoul")
    fun runKr() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        if (!marketSessionService.isKrTradingDay(today)) {
            log.debug("KR watchlist alert skipped — non-trading day {}", today)
            return
        }
        runCatching { watchlistAlertService.scanAndNotify(market = "KR") }
            .onFailure { log.error("KR watchlist alert scan failed", it) }
    }

    // 미국장 정규시간(09:00~15:59 ET) 커버용: 15분 주기. 휴장일은 코드에서 가드한다.
    @Scheduled(cron = "0 */15 9-15 * * MON-FRI", zone = "America/New_York")
    fun runUs() {
        val today = LocalDate.now(ZoneId.of("America/New_York"))
        if (!marketSessionService.isUsTradingDay(today)) {
            log.debug("US watchlist alert skipped — non-trading day {}", today)
            return
        }
        runCatching { watchlistAlertService.scanAndNotify(market = "US") }
            .onFailure { log.error("US watchlist alert scan failed", it) }
    }
}
