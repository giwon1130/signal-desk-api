package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** All first-party market commentary uses the same inputs/rules; no prompt independently judges direction. */
@Service
class EvidenceBriefingService(
    private val yahoo: YahooQuoteClient,
    private val fred: FredIndexClient,
    private val news: GoogleNewsRssClient,
    private val analyzer: MarketEvidenceAnalyzer,
    private val narrator: EvidenceNarrator,
    private val archive: ObjectProvider<MarketEvidenceArchive>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable(cacheNames = ["market-insight"], key = "'evidence-brief-v1'", sync = true)
    fun current(): MarketInsightAnalysis {
        // Do not run parents on the bounded httpFetchExecutor: Yahoo/RSS submit their children there.
        val quotes = CompletableFuture.supplyAsync { runCatching { yahoo.fetchIndices(YahooQuoteClient.BRIEFING_INDICES) }.getOrDefault(emptyList()) }
        val macro = CompletableFuture.supplyAsync { runCatching { fred.fetchMacro() }.getOrNull() }
        val headlines = CompletableFuture.supplyAsync { runCatching { news.fetchMarketNews() }.getOrNull() }
        val collectedQuotes = quotes.join()
        val collectedMacro = macro.join()
        val collectedNews = headlines.join()
        val asOf = Instant.now()
        // Archive exactly the bounded input the analyzer sees, so replay cannot lose selected headlines.
        val boundedNews = collectedNews?.let { analyzer.recentNews(it, asOf) }
            ?.map { it.copy(title = it.title.take(300), impact = "") }
        val input = MarketEvidenceInput(asOf, collectedQuotes, collectedMacro, boundedNews)
        val report = analyzer.analyze(input)
        runCatching { archive.ifAvailable?.save(input, report) }
            .onFailure { log.warn("Market evidence archive failed; briefing remains available ({})", it.javaClass.simpleName) }
        log.info("Market evidence rules={} regime={} coverage={} risk={}", report.rulesVersion, report.regime, report.coveragePercent, report.riskLevel)
        return narrator.narrate(report)
    }
}
