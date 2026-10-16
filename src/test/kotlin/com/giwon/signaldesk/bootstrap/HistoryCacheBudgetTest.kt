package com.giwon.signaldesk.bootstrap

import com.github.benmanes.caffeine.cache.Cache
import com.giwon.signaldesk.features.backtest.application.*
import com.giwon.signaldesk.features.market.application.HistoryBar
import com.giwon.signaldesk.features.market.application.YahooQuoteClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.AopTestUtils
import java.time.LocalDate

class HistoryCacheBudgetTest {
    @Test fun `history retention is bounded by total bars rather than ticker count`() {
        val springCache = CacheConfig().cacheManager().getCache("yahoo-history")!!
        @Suppress("UNCHECKED_CAST")
        val cache = springCache.nativeCache as Cache<Any, Any>
        val bar = HistoryBar(LocalDate.of(2026, 1, 2), 100.0)
        repeat(8) { cache.put("ticker:$it", List(20_000) { bar }) }
        cache.cleanUp()
        assertThat(cache.policy().eviction().get().weightedSize().asLong).isLessThanOrEqualTo(60_000)
        assertThat(cache.estimatedSize()).isBetween(1L, 3L)
        assertThat(cache.asMap().values).allMatch { (it as List<*>).size == 20_000 }
    }

    @Test fun `sector report still computes on demand and reuses result without warm scheduler`() {
        ApplicationContextRunner()
            .withUserConfiguration(CacheConfig::class.java, SeasonalityBacktestService::class.java, SectorRotationService::class.java)
            .withBean(YahooQuoteClient::class.java, { mock(YahooQuoteClient::class.java) })
            .run { context ->
                val yahoo = AopTestUtils.getUltimateTargetObject<YahooQuoteClient>(context.getBean(YahooQuoteClient::class.java))
                val bars = (0 until 3_900).map { HistoryBar(LocalDate.of(2010, 1, 1).plusDays(it.toLong()), 100.0 + it * .01) }
                `when`(yahoo.fetchDailyHistory(anyString(), anyString())).thenReturn(bars)
                val service = context.getBean(SectorRotationService::class.java)
                val first = service.report("US")
                assertThat(first?.sectors).hasSize(11)
                assertThat(service.report("US")).isSameAs(first)
                verify(yahoo, times(11)).fetchDailyHistory(anyString(), anyString())
                assertThat(context).doesNotHaveBean(SectorRotationWarmupScheduler::class.java)
            }
    }
}
