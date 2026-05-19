package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.CboeVixClient
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * VIX + FRED 지수 + 뉴스 헤드라인을 Gemini로 종합해 오늘의 마켓 인사이트를 생성.
 *
 * - 캐시 TTL: 30분 (market-insight)
 * - 의존 데이터는 모두 이미 개별 캐시(macro-index, rss-feed)가 있어서 추가 API 비용 없음.
 * - Gemini 미설정이면 null 반환.
 */
@Service
class MarketInsightService(
    private val cboeVixClient: CboeVixClient,
    private val fredIndexClient: FredIndexClient,
    private val newsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable("market-insight")
    fun getTodayInsight(): MarketInsightAnalysis? {
        if (!geminiClient.isEnabled()) {
            log.info("MarketInsightService skipped — Gemini 미설정")
            return null
        }
        val vix = runCatching { cboeVixClient.fetchVix() }.getOrNull()
        val indices = runCatching { fredIndexClient.fetchUsIndices() }.getOrNull()
        val headlines = runCatching { newsRssClient.fetchMarketNews() }.getOrNull() ?: emptyList()

        return runCatching {
            geminiClient.summarizeMarketInsight(vix, indices, headlines)
        }.getOrElse {
            log.warn("MarketInsightService Gemini call failed", it)
            null
        }
    }
}
