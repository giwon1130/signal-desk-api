package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * AI 시황 흐름 리딩 스케줄러 (KR 거래일에만).
 *  - 장전 08:50 KST — 간밤 미국장 + 전일 수급·섹터로 오늘 흐름 셋업
 *  - 마감 15:50 KST — 오늘 섹터 승자 + 당일 수급(가장 강한 신호)
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class FlowReadingScheduler(
    private val service: FlowReadingService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private fun isKrTradingDay() = marketSessionService.isKrTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")))

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    fun runPreopen() {
        if (!isKrTradingDay()) return
        runCatching { service.runFlow(FlowReadingService.Slot.PREOPEN) }
            .onFailure { log.error("Flow reading(PREOPEN) scheduler failed", it) }
    }

    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Seoul")
    fun runClose() {
        if (!isKrTradingDay()) return
        runCatching { service.runFlow(FlowReadingService.Slot.CLOSE) }
            .onFailure { log.error("Flow reading(CLOSE) scheduler failed", it) }
    }
}
