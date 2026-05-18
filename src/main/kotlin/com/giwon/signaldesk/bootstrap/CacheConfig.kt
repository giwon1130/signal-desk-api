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
        return manager
    }

    private fun build(ttl: Duration, max: Long) =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(max)
            .build<Any, Any>()
}
