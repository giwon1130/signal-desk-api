package com.giwon.signaldesk.bootstrap

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 외부 API 호출 결과를 메모리에 캐싱한다. 캐시 이름별 TTL 분리.
 * 데이터 변동 주기에 맞춰 너무 stale 하지 않게 짧게 둠.
 */
@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager {
        val manager = CaffeineCacheManager()
        manager.isAllowNullValues = true
        manager.registerCustomCache("quote-short", build(Duration.ofSeconds(45), 500))
        manager.registerCustomCache("chart-mid", build(Duration.ofMinutes(5), 500))
        manager.registerCustomCache("search-long", build(Duration.ofHours(1), 1000))
        manager.registerCustomCache("rss-feed", build(Duration.ofMinutes(15), 200))
        manager.registerCustomCache("macro-index", build(Duration.ofMinutes(30), 100))
        manager.registerCustomCache("krx-official", build(Duration.ofMinutes(5), 200))
        manager.registerCustomCache("top-movers", build(Duration.ofMinutes(2), 100))
        manager.registerCustomCache("market-insight", build(Duration.ofMinutes(30), 10))
        // FRED 매크로·네이버 수급 — 일 단위로 갱신되는 데이터. 미등록 시 TTL 없는 영구 캐시가 됨.
        manager.registerCustomCache("macro-snapshot", build(Duration.ofHours(6), 10))
        manager.registerCustomCache("investor-rank", build(Duration.ofHours(6), 50))
        manager.registerCustomCache("ai-picks", build(Duration.ofMinutes(30), 5))
        // 시즈널리티 백테스트 — 장기 히스토리 기반이라 거의 안 변함. 일 단위 캐시.
        manager.registerCustomCache("seasonality", build(Duration.ofHours(24), 500))
        // 야후 장기 일봉(15y≈3800봉) — 가설 빌더가 클릭마다 풀 fetch 하던 것 방지.
        // 엔트리가 커서(수백 KB) 개수 상한을 작게 — 60개 ≈ 섹터 ETF 18종 + 개별 종목 여유분.
        manager.registerCustomCache("yahoo-history", build(Duration.ofHours(6), 60))
        return manager
    }

    private fun build(ttl: Duration, max: Long) =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(max)
            .build<Any, Any>()
}
