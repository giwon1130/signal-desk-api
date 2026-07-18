package com.giwon.signaldesk.features.rounds.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/** 휴장일에는 실행하지 않는 반도체 변동성 라운드 자동화 스케줄. */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class SemiconductorRoundAutomationScheduler(
    private val automation: SemiconductorRoundAutomationService,
    private val sessions: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 10 10,14 * * MON-FRI", zone = "Asia/Seoul")
    fun scanKr() {
        if (!sessions.isKrTradingDay(LocalDate.now(KST))) return
        runCatching { automation.runKr() }.onFailure { log.warn("KR semiconductor round automation failed", it) }
    }

    @Scheduled(cron = "0 45 6 * * TUE-SAT", zone = "Asia/Seoul")
    fun scanUs() {
        if (!sessions.isUsTradingDay(LocalDate.now(NY))) return
        runCatching { automation.runUs() }.onFailure { log.warn("US semiconductor round automation failed", it) }
    }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val NY: ZoneId = ZoneId.of("America/New_York")
    }
}
