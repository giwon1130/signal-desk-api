package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.common.KST
import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 일별 스냅샷 적재 스케줄.
 *  - 16:40 KST: 한국장 마감(15:30) 후 — KR 지수/픽/포트폴리오는 당일 확정값,
 *    US 는 전일 마감값으로 기록된다 (스냅샷은 "그 시점의 상태" 박제가 목적이라 OK).
 * upsert(멱등)라 수동 트리거(/api/v1/snapshots/run)와 겹쳐도 안전.
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DailySnapshotScheduler(
    private val service: DailySnapshotService,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 40 16 * * MON-FRI", zone = "Asia/Seoul")
    fun run() {
        val today = LocalDate.now(KST)
        if (!marketSessionService.isKrTradingDay(today)) {
            log.debug("daily snapshot skipped — KR non-trading day {}", today)
            return
        }
        runCatching { service.runDailySnapshot() }
            .onFailure { log.error("daily snapshot run failed", it) }
    }
}
