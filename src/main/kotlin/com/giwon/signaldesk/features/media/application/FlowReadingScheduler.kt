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
    private val reportCallService: com.giwon.signaldesk.features.reading.application.ReportCallService,
    private val marketSessionService: MarketSessionService,
    private val adminAlert: com.giwon.signaldesk.features.admin.AdminAlertService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private fun isKrTradingDay() = marketSessionService.isKrTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")))

    private fun onFail(subject: String, e: Throwable) {
        log.error("{} failed", subject, e)
        adminAlert.notifyFailure(subject, e.message ?: e.javaClass.simpleName)
    }

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    fun runPreopen() {
        if (!isKrTradingDay()) return
        runCatching { service.runFlow(FlowReadingService.Slot.PREOPEN) }
            .onFailure { onFail("Flow reading(PREOPEN)", it) }
    }

    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Seoul")
    fun runClose() {
        if (!isKrTradingDay()) return
        runCatching { service.runFlow(FlowReadingService.Slot.CLOSE) }
            .onFailure { onFail("Flow reading(CLOSE)", it) }
    }

    // 유튜브 방송(삼프로TV) 자막 요약 스케줄 제거 — 저작권/부정경쟁 리스크로 기능 폐지(2026-06).
    // 자막 수집·요약·발행 경로 전체 중단. (YoutubeFlowReadingService 는 빈 채널 설정으로 no-op.)

    /** 📈 AI 리포트 콜 — 장 마감 후 16:30, 그날 신규 증권사 목표주가 리포트를 콜로 발행. */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Seoul")
    fun runReportCalls() {
        if (!isKrTradingDay()) return
        runCatching { reportCallService.run() }.onFailure { onFail("Report calls(16:30)", it) }
    }
}
