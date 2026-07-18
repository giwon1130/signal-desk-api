package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/** 장전 입력 박제와 시초 결과 평가를 분리한 스케줄러. */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class PreMarketDirectionForecastScheduler(
    private val forecastService: PreMarketDirectionForecastService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val koreaZone = ZoneId.of("Asia/Seoul")

    private fun isKrTradingDay(today: LocalDate) = marketSessionService.isKrTradingDay(today)

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    fun capture() {
        val today = LocalDate.now(koreaZone)
        if (!isKrTradingDay(today)) {
            log.debug("premarket forecast capture skipped — non-trading day {}", today)
            return
        }
        runCatching { forecastService.capture(today) }
            .onFailure { log.error("premarket forecast capture failed", it) }
    }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Seoul")
    fun evaluate() {
        val today = LocalDate.now(koreaZone)
        if (!isKrTradingDay(today)) {
            log.debug("premarket forecast evaluation skipped — non-trading day {}", today)
            return
        }
        runCatching { forecastService.evaluate(today) }
            .onFailure { log.error("premarket forecast evaluation failed", it) }
    }
}
