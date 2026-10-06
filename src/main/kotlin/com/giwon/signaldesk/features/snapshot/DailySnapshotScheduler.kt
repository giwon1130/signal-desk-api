package com.giwon.signaldesk.features.snapshot

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 거래일별 기록. 두 번째 실행은 누락 자료만 보충하고, 이미 저장한 기록은 덮어쓰지 않는다.
 * 미국은 제공자 일봉의 미확정 표시가 해제되는 현지 날짜 변경 이후 수집한다.
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DailySnapshotScheduler(
    private val service: DailySnapshotService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 40 16,17 * * MON-FRI", zone = "Asia/Seoul")
    fun run() = capture("KR")

    // 토요일도 포함: 금요일 미국장 종가. DST는 미국 동부 시간대가 처리한다.
    @Scheduled(cron = "0 40 0,1 * * *", zone = "America/New_York")
    fun runUs() = capture("US")

    private fun capture(market: String) {
        runCatching { service.runDailySnapshot(market) }
            .onFailure { log.error("snapshot run failed market={} type={}", market, it.javaClass.simpleName) }
    }
}
