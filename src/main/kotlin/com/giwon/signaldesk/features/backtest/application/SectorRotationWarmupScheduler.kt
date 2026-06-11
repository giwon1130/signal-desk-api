package com.giwon.signaldesk.features.backtest.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 섹터 로테이션 캐시 워밍 — 콜드 캐시 첫 조회는 ETF 7~11개 × 15y 히스토리를 모아
 * 수십 초가 걸린다. 사용자가 그 비용을 내지 않게 미리 데운다.
 *  - 매일 05:30 KST: seasonality 캐시(24h) 만료 직후 재계산.
 *  - 부팅 3분 후 1회: 배포마다 인메모리 캐시가 날아가므로 재배포 직후도 커버.
 * report() 는 @Cacheable — 프록시 호출로 결과가 캐시에 들어간다.
 */
@Component
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
