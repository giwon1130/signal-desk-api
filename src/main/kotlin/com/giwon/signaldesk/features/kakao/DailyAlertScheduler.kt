package com.giwon.signaldesk.features.kakao

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Component
class DailyAlertScheduler(
    private val sellSignalService: SellSignalService,
    private val shortTermPickService: ShortTermPickService,
    private val alertStore: KakaoAlertStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 평일 오전 8:30 KST 실행 (장 전)
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    fun runDailyAlert() {
        log.info("Daily alert scheduler started")
        runCatching {
            val signals = sellSignalService.evaluate()
            val picks = shortTermPickService.picks(limit = 5)
            val result = DailyAlertResult(
                generatedAt = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
                    .format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                sellSignals = signals,
                shortTermPicks = picks,
            )
            alertStore.save(result)
            log.info("Daily alert saved: signals={}, picks={}", signals.size, picks.size)
        }.onFailure { log.error("Daily alert failed", it) }
    }
}
