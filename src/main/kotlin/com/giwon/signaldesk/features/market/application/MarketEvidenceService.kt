package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.features.media.application.MarketEvidenceArchive
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** One bounded, shared snapshot for cards, pre-open review, briefing and risk alerts. No LLM here. */
@Service
class MarketEvidenceService(
    private val yahoo: YahooQuoteClient,
    private val fred: FredIndexClient,
    private val news: GoogleNewsRssClient,
    private val analyzer: MarketEvidenceAnalyzer,
    private val archive: ObjectProvider<MarketEvidenceArchive>,
    @Autowired(required = false) private val nightFeed: KrxNightFuturesFeed? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var cached: MarketEvidenceReport? = null

    @Synchronized fun current(): MarketEvidenceReport {
        cached?.takeIf { Duration.between(Instant.parse(it.asOf), Instant.now()).seconds in 0..59 }?.let { return it }
        // Parents must not occupy the bounded executor used by the clients' child requests.
        val quotes = CompletableFuture.supplyAsync { runCatching { yahoo.fetchIndices(YahooQuoteClient.BRIEFING_INDICES) }.getOrDefault(emptyList()) }.orTimeout(12, TimeUnit.SECONDS)
        val macro = CompletableFuture.supplyAsync { runCatching { fred.fetchMacro() }.getOrNull() }.orTimeout(12, TimeUnit.SECONDS)
        val headlines = CompletableFuture.supplyAsync { runCatching { news.fetchMarketNews() }.getOrNull() }.orTimeout(12, TimeUnit.SECONDS)
        val nightResult = CompletableFuture.supplyAsync { nightFeed?.snapshot() }.orTimeout(12, TimeUnit.SECONDS)
        val collectedQuotes = runCatching { quotes.get(12, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val collectedMacro = runCatching { macro.get(12, TimeUnit.SECONDS) }.getOrNull()
        val collectedNews = runCatching { headlines.get(12, TimeUnit.SECONDS) }.getOrNull()
        val night = runCatching { nightResult.get(12, TimeUnit.SECONDS) }.onFailure {
            log.warn("Night futures feed unavailable ({})", it.javaClass.simpleName)
        }.getOrNull()
        val now = Instant.now()
        val boundedNews = collectedNews?.let { analyzer.recentNews(it, now) }
            ?.map { it.copy(title = it.title.take(300), impact = "") }
        val input = MarketEvidenceInput(now, collectedQuotes, collectedMacro, boundedNews, night)
        val report = analyzer.analyze(input)
        runCatching { archive.ifAvailable?.save(input, report) }
            .onFailure { log.warn("Market evidence archive unavailable ({})", it.javaClass.simpleName) }
        cached = report
        return report
    }
}
