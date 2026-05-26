package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class EveningBriefScheduler(
    private val service: EveningBriefService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 06:30 KST = NY 장 마감(16:00 ET = 일반시기 05:00 KST, DST 06:00 KST) 직후 ~30분 마진.
     * US 거래일 다음 날 오전에 발송 — 화-토 (월요일 KST = NY 일요일이므로 제외).
     */
    @Scheduled(cron = "0 30 6 * * TUE-SAT", zone = "Asia/Seoul")
    fun runDaily() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        // 어제(NY 영업일)가 미국 거래일이었는지 확인 — 휴장이면 skip.
        val yesterday = today.minusDays(1)
        if (!marketSessionService.isUsTradingDay(yesterday)) {
            log.debug("Evening brief skipped — yesterday {} was US non-trading day", yesterday)
            return
        }
        runCatching { service.runBrief() }
            .onFailure { log.error("Evening brief scheduler failed", it) }
    }
}
