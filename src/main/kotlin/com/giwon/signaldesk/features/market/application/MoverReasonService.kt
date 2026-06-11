package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.common.KST

import com.giwon.signaldesk.features.media.application.GeminiClient
import com.giwon.signaldesk.features.media.application.MoverReasonInput
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/**
 * 급등/급락 사유 서비스 — "왜 올랐나/내렸나".
 *
 * 비용 최소화: 이미 캐시된 top movers + 시장 뉴스(GoogleNewsRssClient)를 재사용해
 * 상위 급등락 종목에 헤드라인을 매칭한 뒤 Gemini 배치 1회로 종목별 한 줄 사유를 생성.
 * 결과는 인메모리로 TTL 캐시하고, 스케줄러로 미리 데워 사용자 요청은 즉시 응답.
 */
@Service
class MoverReasonService(
    private val topMoversService: TopMoversService,
    private val newsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
    private val marketSessionService: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var cache: Cached? = null
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Cached(val at: Instant, val list: List<MoverReason>)

    /**
     * 캐시가 신선하면 그대로. stale 이면 즉시 직전 캐시를 응답하고 백그라운드 single-flight 로
     * 재계산 — 사용자 요청이 Gemini 파이프라인(최악 수십 초)을 기다리지 않게.
     * 캐시가 아예 없을 때(부팅 직후 1회)만 동기 계산.
     */
    fun reasons(): List<MoverReason> {
        val c = cache
        if (c != null && Duration.between(c.at, Instant.now()).toMinutes() < TTL_MINUTES) return c.list
        if (c == null) {
            return runCatching { compute() }.getOrElse {
                log.warn("mover reasons compute failed", it)
                emptyList()
            }
        }
        refreshAsync()
        return c.list
    }

    private fun refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return
        java.util.concurrent.CompletableFuture.runAsync {
            try {
                runCatching { compute() }.onFailure { log.warn("mover reasons refresh failed", it) }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * 사용자 요청이 Gemini 호출을 기다리지 않도록 미리 데운다.
     * KR 또는 US 정규장(각 휴장일 제외) 동안만 데운다 — 24시간 워밍은 Gemini 무료 쿼터를
     * 불필요하게 소진(96회/일)했다. KR 6.5h + US 6.5h × 15분 ≈ 52회/일로 감축(이 잡은 KR·US
     * 급등락을 모두 다루므로 양 시장 정규장을 커버). 장 밖에선 직전 캐시 노출.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 45 * 1000L)
    fun warm() {
        if (!isAnyRegularSession()) return
        runCatching { reasons() }.onFailure { log.debug("mover reasons warm skipped", it) }
    }

    private fun isAnyRegularSession(): Boolean =
        marketSessionService.buildMarketSessions()
            .any { (it.market == "KR" || it.market == "US") && it.phase == "REGULAR" }

    private fun compute(): List<MoverReason> {
        if (!geminiClient.isEnabled()) return cache?.list ?: emptyList()

        val movers = topMoversService.fetchTopMovers(5)
        val picks = selectPicks(movers)
        if (picks.isEmpty()) {
            cache = Cached(Instant.now(), emptyList())
            return emptyList()
        }

        // 1차: 이미 캐시된 시장 뉴스 풀에서 종목명 매칭(추가 호출 0). 상위 급등락주 대부분 여기서 잡힌다.
        val pool = newsRssClient.fetchMarketNews().orEmpty()
        val matchedByTicker = picks.associate { p ->
            p.ticker to pool.asSequence().filter { matchesName(p.name, p.ticker, it) }
                .map { it.title }.distinct().take(4).toList()
        }
        // 2차: 풀에서 못 잡은 종목만 종목명으로 직접 검색(병렬). 비용을 미스에만 한정하고
        // "종목명 주가"/"name stock"으로 검색해 동명이의(예: 'M83') 오매칭을 줄인다.
        val fetchedByTicker = picks.filter { matchedByTicker[it.ticker].isNullOrEmpty() }
            .associate { p ->
                p.ticker to java.util.concurrent.CompletableFuture.supplyAsync {
                    runCatching { newsRssClient.fetchByQuery(p.market, moverQuery(p.market, p.name)) }
                        .getOrElse { emptyList() }.asSequence().map { it.title }.distinct().take(4).toList()
                }
            }.mapValues { it.value.join() }

        val inputs = picks.map { p ->
            val headlines = matchedByTicker[p.ticker].orEmpty().ifEmpty { fetchedByTicker[p.ticker].orEmpty() }
            MoverReasonInput(
                market = p.market,
                ticker = p.ticker,
                name = p.name,
                changeRate = p.changeRate,
                direction = if (p.changeRate >= 0) "급등" else "급락",
                headlines = headlines,
            )
        }

        val byTicker = geminiClient.summarizeMoverReasons(LocalDate.now(KST).toString(), inputs)
            .associateBy { it.ticker.trim() }

        val result = picks.mapNotNull { p ->
            val reason = byTicker[p.ticker.trim()]?.reason?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MoverReason(
                market = p.market,
                ticker = p.ticker,
                name = p.name,
                direction = if (p.changeRate >= 0) "UP" else "DOWN",
                changeRate = p.changeRate,
                reason = reason,
            )
        }
        cache = Cached(Instant.now(), result)
        log.info("mover reasons computed: {}/{} picks got a reason", result.size, picks.size)
        return result
    }

    /** KR(KOSPI+KOSDAQ 통합) / US 각각 급등 top3 + 급락 top3, |등락률| 임계 이상만. */
    private fun selectPicks(movers: TopMoversResponse): List<TopMover> {
        val krGainers = (movers.kospi.gainers + movers.kosdaq.gainers).sortedByDescending { it.changeRate }
        val krLosers = (movers.kospi.losers + movers.kosdaq.losers).sortedBy { it.changeRate }
        val picks = buildList {
            addAll(krGainers.take(TOP_N))
            addAll(krLosers.take(TOP_N))
            addAll(movers.us.gainers.sortedByDescending { it.changeRate }.take(TOP_N))
            addAll(movers.us.losers.sortedBy { it.changeRate }.take(TOP_N))
        }
        return picks
            .filter { abs(it.changeRate) >= MIN_MOVE_PCT }
            .distinctBy { "${it.market}:${it.ticker}" }
    }

    /**
     * 임의 종목(관심종목 급등락 등)에 대해 뉴스 매칭 + Gemini 한 줄 사유.
     * 캐시된 시장 뉴스를 재사용하고 Gemini 배치 1회만 호출한다. ticker→사유 맵 반환(공백 제외).
     */
    fun reasonsForTickers(targets: List<MoverReasonTarget>): Map<String, String> {
        if (!geminiClient.isEnabled() || targets.isEmpty()) return emptyMap()
        // 종목명으로 직접 뉴스 검색(병렬) — 시장 단위 풀엔 개별 종목 헤드라인이 거의 없어
        // 매칭이 0건→'추정'만 나왔다. 종목 쿼리로 실제 헤드라인을 확보해야 진짜 사유가 나온다.
        // 과도한 호출 방지로 최대 MAX_REASON_TARGETS 개만 처리.
        val capped = targets.take(MAX_REASON_TARGETS)
        val inputs = capped.map { t ->
            java.util.concurrent.CompletableFuture.supplyAsync {
                val related = runCatching { newsRssClient.fetchByQuery(t.market, moverQuery(t.market, t.name)) }
                    .getOrElse { emptyList() }.asSequence().map { it.title }.distinct().take(4).toList()
                MoverReasonInput(
                    market = t.market, ticker = t.ticker, name = t.name,
                    changeRate = t.changeRate,
                    direction = if (t.changeRate >= 0) "급등" else "급락",
                    headlines = related,
                )
            }
        }.map { it.join() }
        return runCatching {
            geminiClient.summarizeMoverReasons(LocalDate.now(KST).toString(), inputs)
                .associate { it.ticker.trim() to it.reason.trim() }
                .filterValues { it.isNotBlank() }
        }.getOrElse { log.warn("watch mover reasons failed", it); emptyMap() }
    }

    /** 헤드라인 제목에 종목명 또는 티커가 포함되면 관련 뉴스로 본다(시장 뉴스 풀 1차 매칭용). */
    private fun matchesName(name: String, ticker: String, news: MarketNews): Boolean {
        val title = news.title.lowercase()
        val n = name.lowercase()
        val t = ticker.lowercase()
        return (n.length >= 2 && title.contains(n)) || (t.length >= 2 && title.contains(t))
    }

    /** 종목별 뉴스 검색 쿼리 — 동명이의 오매칭을 줄이려 시장 맥락을 덧붙인다. */
    private fun moverQuery(market: String, name: String): String =
        if (market == "US") "$name stock" else "$name 주가"

    companion object {
        private const val TTL_MINUTES = 15L
        private const val TOP_N = 3
        private const val MIN_MOVE_PCT = 3.0
        private const val MAX_REASON_TARGETS = 12   // 워치 알림 사유 — 종목별 뉴스 검색 호출 상한
    }
}

/** 급등/급락 사유 응답 DTO. */
data class MoverReason(
    val market: String,
    val ticker: String,
    val name: String,
    val direction: String,
    val changeRate: Double,
    val reason: String,
)

/** reasonsForTickers 입력 — 사유를 붙일 임의 종목(관심 급등락 등). */
data class MoverReasonTarget(
    val market: String,
    val ticker: String,
    val name: String,
    val changeRate: Double,
)
