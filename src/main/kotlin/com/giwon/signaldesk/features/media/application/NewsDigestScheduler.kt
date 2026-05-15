package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class NewsDigestScheduler(
    private val newsDigestService: NewsDigestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // KR 장 마감 후 (15:40 KST) — 마감 직후 헤드라인 안정화 시간 고려
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    fun runKrClose() {
        runCatching { newsDigestService.runDigest("KR") }
            .onFailure { log.error("KR news digest scheduled run failed", it) }
    }

    // US 장 마감 후 한국 시간 (다음날 06:30 KST = US ET 16:30 마감 + 1시간) — 미국 마감시황
    @Scheduled(cron = "0 30 6 * * TUE-SAT", zone = "Asia/Seoul")
    fun runUsClose() {
        runCatching { newsDigestService.runDigest("US") }
            .onFailure { log.error("US news digest scheduled run failed", it) }
    }
}
