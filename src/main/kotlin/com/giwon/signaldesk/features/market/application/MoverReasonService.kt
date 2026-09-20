package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** 가격 변동과 관련 보도를 구분한다. 뉴스 유무와 관계없이 원인을 추측해 생성하지 않는다. */
@Service
class MoverReasonService(
    private val topMoversService: TopMoversService,
    private val newsRssClient: GoogleNewsRssClient,
    private val marketSessionService: MarketSessionService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var cache: Cached? = null
    private val refreshing = AtomicBoolean(false)
    private data class Cached(val at: Instant, val list: List<MoverReason>)

    fun reasons(): List<MoverReason> {
        val cached = cache
        if (cached != null && Duration.between(cached.at, clock.instant()) < Duration.ofMinutes(TTL_MINUTES)) return cached.list
        if (cached == null) return runCatching { compute() }.getOrElse {
            log.warn("mover context compute failed", it)
            emptyList()
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
        val pool = runCatching { newsRssClient.fetchMarketNews().orEmpty() }.getOrDefault(emptyList())
        val contexts = contextsFor(targets, pool)
        val result = picks.map { p ->
            MoverReason(p.market, p.ticker, p.name, if (p.changeRate >= 0) "UP" else "DOWN", p.changeRate,
                contexts[p.market to p.ticker] ?: MoverNewsEvidence.UNKNOWN_CAUSE)
        }
        cache = Cached(clock.instant(), result)
        return result
    }

    /** 키에 시장을 포함해 같은 티커를 가진 다른 시장의 보도가 섞이지 않게 한다. */
    fun reasonsForTickers(targets: List<MoverReasonTarget>): Map<Pair<String, String>, String> =
        contextsFor(targets.distinctBy { it.market to it.ticker }.take(MAX_REASON_TARGETS), emptyList())

    private fun contextsFor(targets: List<MoverReasonTarget>, pool: List<MarketNews>): Map<Pair<String, String>, String> =
        targets.map { target ->
            CompletableFuture.supplyAsync {
                val fromPool = MoverNewsEvidence.latest(target, pool, clock.instant())
                val item = fromPool ?: runCatching {
                    val query = if (target.market == "US") "${target.name} stock when:1d" else "${target.name} 주가 when:1d"
                    MoverNewsEvidence.latest(target, newsRssClient.fetchByQuery(target.market, query), clock.instant())
                }.getOrNull()
                (target.market to target.ticker) to MoverNewsEvidence.describe(item)
            }
        }.associate { it.join() }

    private fun selectPicks(movers: TopMoversResponse): List<TopMover> = buildList {
        addAll((movers.kospi.gainers + movers.kosdaq.gainers).sortedByDescending { it.changeRate }.take(TOP_N))
        addAll((movers.kospi.losers + movers.kosdaq.losers).sortedBy { it.changeRate }.take(TOP_N))
        addAll(movers.us.gainers.sortedByDescending { it.changeRate }.take(TOP_N))
        addAll(movers.us.losers.sortedBy { it.changeRate }.take(TOP_N))
    }.filter { it.changeRate.isFinite() && abs(it.changeRate) >= MIN_MOVE_PCT }
        .distinctBy { it.market to it.ticker }

    companion object {
        private const val TTL_MINUTES = 15L
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
)

data class MoverReasonTarget(val market: String, val ticker: String, val name: String, val changeRate: Double)
