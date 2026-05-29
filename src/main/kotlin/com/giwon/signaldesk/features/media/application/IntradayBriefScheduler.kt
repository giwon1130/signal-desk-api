package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * KR 장중/마감 브리프 스케줄러.
 *  - 장중: 12:30 KST (오전장 마감 직후, 점심 점검)
 *  - 마감: 15:40 KST (KR 정규장 마감 15:30 직후)
 * 한국 거래일에만 실행 (휴장일 코드 가드).
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class IntradayBriefScheduler(
    private val service: IntradayBriefService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 12 * * MON-FRI", zone = "Asia/Seoul")
    fun runMidday() {
        if (!marketSessionService.isKrTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")))) return
        runCatching { service.runBrief(IntradayBriefService.Slot.MIDDAY) }
            .onFailure { log.error("Midday brief scheduler failed", it) }
    }

    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    fun runClose() {
        if (!marketSessionService.isKrTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")))) return
        runCatching { service.runBrief(IntradayBriefService.Slot.CLOSE) }
            .onFailure { log.error("Close brief scheduler failed", it) }
    }
}
