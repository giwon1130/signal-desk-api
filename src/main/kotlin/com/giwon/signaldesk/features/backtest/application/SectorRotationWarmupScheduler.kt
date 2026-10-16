package com.giwon.signaldesk.features.backtest.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 선택적 섹터 로테이션 캐시 워밍. 기본 비활성: 실제 조회 없이 18개 ETF의 장기
 * 히스토리를 매일 수집/계산하지 않는다. 조회 API의 계산·캐시는 계속 동작한다.
 *  - 매일 05:30 KST: seasonality 캐시(24h) 만료 직후 재계산.
 *  - 부팅 3분 후 1회: 배포마다 인메모리 캐시가 날아가므로 재배포 직후도 커버.
 * report() 는 @Cacheable — 프록시 호출로 결과가 캐시에 들어간다.
 */
@Component
@ConditionalOnProperty(
    prefix = "signal-desk.scheduling.sector-rotation-warm",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class SectorRotationWarmupScheduler(
    private val sectorRotationService: SectorRotationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 5 * * *", zone = "Asia/Seoul")
    @Scheduled(initialDelay = 3 * 60 * 1000L, fixedDelay = Long.MAX_VALUE)
    fun warm() {
        for (market in listOf("US", "KR")) {
            val started = System.currentTimeMillis()
            runCatching { sectorRotationService.report(market) }
                .onSuccess {
                    log.info("sector rotation warmed — market={} sectors={} took={}ms",
                        market, it?.sectors?.size ?: 0, System.currentTimeMillis() - started)
                }
                .onFailure { log.warn("sector rotation warm failed — market={}", market, it) }
        }
    }
}
