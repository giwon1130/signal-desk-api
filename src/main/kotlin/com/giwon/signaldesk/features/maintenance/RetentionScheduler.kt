package com.giwon.signaldesk.features.maintenance

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 매일 03:50 KST(트래픽 한산) 보존 정리 1회. DisclosureSeen 정리(03:30)와 시간대 분리. */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class RetentionScheduler(
    private val retentionService: RetentionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 50 3 * * *", zone = "Asia/Seoul")
    fun runDaily() {
        runCatching { retentionService.runRetention() }
            .onFailure { log.error("retention 정리 실패", it) }
    }
}
