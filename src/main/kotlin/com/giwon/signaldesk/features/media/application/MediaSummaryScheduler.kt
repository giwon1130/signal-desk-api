package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MediaSummaryScheduler(
    private val mediaSummaryService: MediaSummaryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 19:30 / 22:00 KST 두 번 실행
    //   - 19:30: 장 끝나고 보통 데일리 방송이 올라옴
    //   - 22:00: 늦게 업로드된 방송 캐치업
    @Scheduled(cron = "0 30 19 * * *", zone = "Asia/Seoul")
    fun runEvening() {
        runCatching { mediaSummaryService.runDailyScan() }
            .onFailure { log.error("media summary evening scan failed", it) }
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "Asia/Seoul")
    fun runLate() {
        runCatching { mediaSummaryService.runDailyScan() }
            .onFailure { log.error("media summary late scan failed", it) }
    }
}
