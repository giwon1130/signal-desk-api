package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 가격 변동과 관련 보도를 구분한다. 뉴스 유무와 관계없이 원인을 추측해 생성하지 않는다. */
@Service
class MoverReasonService(
    private val topMoversService: TopMoversService,
    private val newsRssClient: GoogleNewsRssClient,
    private val marketSessionService: MarketSessionService,
    private val clock: Clock = Clock.systemUTC(),
    @Autowired(required = false) private val disclosureSource: MoverDisclosureSource? = null,
    @Autowired(required = false) private val naverNews: NaverNewsSearchClient? = null,
    @Autowired(required = false) private val marketEvidence: MarketEvidenceService? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var cache: Cached? = null
    private val refreshing = AtomicBoolean(false)
    private data class Cached(val at: Instant, val list: List<MoverReason>)
    private data class Collected(val at: Instant, val news: List<MarketNews>, val checks: List<MoveSourceCheck>, val disclosures: MoveDisclosureResult)
    private val perStock = ConcurrentHashMap<String, Collected>()

    fun reasons(): List<MoverReason> {
        val cached = cache
        if (cached != null && Duration.between(cached.at, clock.instant()) < Duration.ofMinutes(TTL_MINUTES)) return cached.list
        if (cached == null) {
            if (!refreshing.compareAndSet(false, true)) return emptyList()
            try { return runCatching { compute() }.getOrElse {
                log.warn("mover context compute failed", it)
                emptyList()
            } } finally { refreshing.set(false) }
        }
        if (refreshing.compareAndSet(false, true)) CompletableFuture.runAsync {
            try {
                runCatching { compute() }.onFailure { log.warn("mover context refresh failed", it) }
            } finally {
                refreshing.set(false)
            }
        }
        // 캐시가 만료되면 옛 기사/등락 방향을 현재 원인으로 재사용하지 않는다.
        return emptyList()
    }

    @Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 45 * 1000L)
    fun warm() {
        if (marketSessionService.buildMarketSessions().none { it.market in setOf("KR", "US") && it.phase == "REGULAR" }) return
        runCatching { reasons() }.onFailure { log.debug("mover context warm skipped", it) }
    }

    private fun compute(): List<MoverReason> {
        val picks = selectPicks(topMoversService.fetchTopMovers(5))
        val targets = picks.map { MoverReasonTarget(it.market, it.ticker, it.name, it.changeRate) }
        val contexts = contextsForTickers(targets)
        val result = picks.map { p ->
            MoverReason(p.market, p.ticker, p.name, if (p.changeRate >= 0) "UP" else "DOWN", p.changeRate,
                contexts[p.market to p.ticker]?.summary ?: StockMoveContextBuilder.UNAVAILABLE,
                contexts[p.market to p.ticker])
        }
        cache = Cached(clock.instant(), result)
        return result
    }

    /** 키에 시장을 포함해 같은 티커를 가진 다른 시장의 보도가 섞이지 않게 한다. */
    fun reasonsForTickers(targets: List<MoverReasonTarget>): Map<Pair<String, String>, String> =
        contextsForTickers(targets).mapValues { it.value.summary }

    fun contextsForTickers(targets: List<MoverReasonTarget>): Map<Pair<String, String>, StockMoveContext> =
        targets.distinctBy { it.market to it.ticker }.take(MAX_REASON_TARGETS).map { target ->
            CompletableFuture.supplyAsync {
                val collected = collect(target)
                (target.market to target.ticker) to StockMoveContextBuilder.build(target, clock.instant(), collected.disclosures,
                    collected.news, collected.checks, runCatching { marketEvidence?.recentSnapshot() }.getOrNull())
            }.orTimeout(20, TimeUnit.SECONDS).exceptionally {
                (target.market to target.ticker) to StockMoveContext(clock.instant().toString(), "UNAVAILABLE", StockMoveContextBuilder.UNAVAILABLE,
                    sourceChecks = listOf(MoveSourceCheck("종목 자료", "UNAVAILABLE")))
            }
        }.associate { it.join() }

    private fun collect(target: MoverReasonTarget): Collected {
        val now = clock.instant()
        // Bound both memory and upstream work. Per-key compute also coalesces concurrent users.
        if (perStock.size >= 512) perStock.entries.minByOrNull { it.value.at }?.let { perStock.remove(it.key, it.value) }
        val key = "${target.market}:${target.ticker}:${target.name}"
        return perStock.compute(key) { _, old ->
            val ttl = if (old?.checks?.any { it.status == "SUCCESS" } == true) 300 else 60
            if (old != null && Duration.between(old.at, now).seconds in 0 until ttl.toLong()) old
            else {
                val queryName = target.name.replace(Regex("[\"\\r\\n]"), " ").trim().take(100)
                val queries = listOf("$queryName when:4d", "$queryName ${if (target.market == "US") "stock" else "주가"} when:4d")
                val results = queries.map { query -> runCatching { newsRssClient.searchWithStatus(target.market, query) }
                    .getOrNull() ?: NewsSearchResult("UNAVAILABLE") }
                val naver = runCatching { naverNews?.search(target) }.getOrNull() ?: NewsSearchResult("DISABLED")
                val filings = runCatching { disclosureSource?.find(target, now) }.getOrElse { MoveDisclosureResult("UNAVAILABLE") }
                    ?: MoveDisclosureResult("DISABLED")
                val at = clock.instant()
                if (results.any { it.status == "UNAVAILABLE" } || filings.status == "UNAVAILABLE")
                    log.debug("Stock context sources partially unavailable market={} ticker={}", target.market, target.ticker)
                Collected(at, (results.flatMap { it.items } + naver.items),
                    results.mapIndexed { i, r -> MoveSourceCheck("Google News ${i + 1}", r.status, at.toString()) } +
                        if (target.market == "KR") listOf(MoveSourceCheck("네이버 뉴스", naver.status, at.toString())) else emptyList(), filings.copy(checkedAt = at.toString()))
            }
        }!!
    }

    private fun selectPicks(movers: TopMoversResponse): List<TopMover> = buildList {
        addAll((movers.kospi.gainers + movers.kosdaq.gainers).sortedByDescending { it.changeRate }.take(TOP_N))
        addAll((movers.kospi.losers + movers.kosdaq.losers).sortedBy { it.changeRate }.take(TOP_N))
        addAll(movers.us.gainers.sortedByDescending { it.changeRate }.take(TOP_N))
        addAll(movers.us.losers.sortedBy { it.changeRate }.take(TOP_N))
    }.filter { it.changeRate.isFinite() && abs(it.changeRate) >= MIN_MOVE_PCT }
        .distinctBy { it.market to it.ticker }

    companion object {
        private const val TTL_MINUTES = 5L
        private const val TOP_N = 3
        private const val MIN_MOVE_PCT = 3.0
        private const val MAX_REASON_TARGETS = 12
    }
}

/** 기존 API 계약 유지. reason은 확인되지 않은 원인과 관련 보도를 명시적으로 구분한다. */
data class MoverReason(
    val market: String,
    val ticker: String,
    val name: String,
    val direction: String,
    val changeRate: Double,
    val reason: String,
    val context: StockMoveContext? = null,
)

data class MoverReasonTarget(val market: String, val ticker: String, val name: String, val changeRate: Double)
