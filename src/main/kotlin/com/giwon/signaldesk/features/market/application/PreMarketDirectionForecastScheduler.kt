package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 장전 입력 박제와 시초 결과 평가를 분리한 스케줄러. */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class PreMarketDirectionForecastScheduler(
    private val forecastService: PreMarketDirectionForecastService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    fun capture() {
        runCatching { forecastService.capture() }
            .onFailure { log.error("premarket forecast capture failed", it) }
    }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Seoul")
    fun evaluate() {
        runCatching { forecastService.evaluate() }
            .onFailure { log.error("premarket forecast evaluation failed", it) }
    }
}
