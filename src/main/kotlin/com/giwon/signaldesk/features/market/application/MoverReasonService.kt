package com.giwon.signaldesk.features.market.application

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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var cache: Cached? = null
    private data class Cached(val at: Instant, val list: List<MoverReason>)

    /** 캐시가 신선하면 그대로, 아니면 동기 재계산(15분에 한 번). 실패 시 직전 캐시 유지. */
    fun reasons(): List<MoverReason> {
        val c = cache
        if (c != null && Duration.between(c.at, Instant.now()).toMinutes() < TTL_MINUTES) return c.list
        return runCatching { compute() }.getOrElse {
            log.warn("mover reasons compute failed", it)
            c?.list ?: emptyList()
        }
    }

    /** 사용자 요청이 Gemini 호출을 기다리지 않도록 미리 데운다. */
    @Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 45 * 1000L)
    fun warm() {
        runCatching { reasons() }.onFailure { log.debug("mover reasons warm skipped", it) }
    }

    private fun compute(): List<MoverReason> {
        if (!geminiClient.isEnabled()) return cache?.list ?: emptyList()

        val movers = topMoversService.fetchTopMovers(5)
        val picks = selectPicks(movers)
        if (picks.isEmpty()) {
            cache = Cached(Instant.now(), emptyList())
            return emptyList()
        }

        val news = newsRssClient.fetchMarketNews().orEmpty()
        val inputs = picks.map { p ->
            val related = news.asSequence()
                .filter { matches(p, it) }
                .map { it.title }
                .distinct()
                .take(4)
                .toList()
            MoverReasonInput(
                market = p.market,
                ticker = p.ticker,
                name = p.name,
                changeRate = p.changeRate,
                direction = if (p.changeRate >= 0) "급등" else "급락",
                headlines = related,
            )
        }

        val byTicker = geminiClient.summarizeMoverReasons(LocalDate.now().toString(), inputs)
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

    /** 헤드라인 제목에 종목명 또는 티커가 포함되면 관련 뉴스로 본다. */
    private fun matches(mover: TopMover, news: MarketNews): Boolean {
        val title = news.title.lowercase()
        val name = mover.name.lowercase()
        val ticker = mover.ticker.lowercase()
        return (name.length >= 2 && title.contains(name)) || (ticker.length >= 2 && title.contains(ticker))
    }

    companion object {
        private const val TTL_MINUTES = 15L
        private const val TOP_N = 3
        private const val MIN_MOVE_PCT = 3.0
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
