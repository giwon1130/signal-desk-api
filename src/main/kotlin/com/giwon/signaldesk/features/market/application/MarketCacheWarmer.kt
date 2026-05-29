package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 대시보드 코어/뉴스 캐시 워머.
 *
 * 문제: coreTtlOpen=60s 라 트래픽이 적으면 캐시가 만료된 직후 첫 사용자 요청이 콜드 리빌드(최대 ~11s)를
 * 그대로 떠안아 메인 화면 로딩이 매우 느려짐.
 * 해결: 30s 주기로 백그라운드에서 만료 전 미리 갱신(refreshAhead) → 사용자 요청은 항상 warm 캐시(~1.2s).
 *
 * 외부 API 호출은 각 sub-client 의 @Cacheable(quote-short 45s, krx 5min, top-movers 2min 등)이
 * 자연스럽게 스로틀하므로 워밍이 외부 호출을 30s마다 모두 재발생시키지는 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MarketCacheWarmer(
    private val marketOverviewService: MarketOverviewService,
    private val topMoversService: TopMoversService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    fun warm() {
        val start = System.currentTimeMillis()
        marketOverviewService.warmUp()
        // top-movers(앱 기본 limit=10)도 워밍 — TodayTab 시장 발견 카드. 2분 sub-cache 가 외부호출 스로틀.
        runCatching { topMoversService.fetchTopMovers(10) }
            .onFailure { log.warn("top-movers warm-up failed: {}", it.message) }
        val took = System.currentTimeMillis() - start
        if (took > 3000) log.info("market cache warm-up took {}ms (cold rebuild absorbed in background)", took)
    }
}
