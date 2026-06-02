package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * KR 마감 브리프 스케줄러.
 *  - 마감: 15:40 KST (KR 정규장 마감 15:30 직후)
 * 한국 거래일에만 실행 (휴장일 코드 가드).
 * (장중 12:30 브리프는 2026-06 제거 — 불필요 판단. Slot.MIDDAY 는 수동 refresh 용으로만 잔존.)
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class IntradayBriefScheduler(
    private val service: IntradayBriefService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    fun runClose() {
        if (!marketSessionService.isKrTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")))) return
        runCatching { service.runBrief(IntradayBriefService.Slot.CLOSE) }
            .onFailure { log.error("Close brief scheduler failed", it) }
    }
}
