package com.giwon.signaldesk.features.ai.application

import com.giwon.signaldesk.features.market.application.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Bounded rule-based screening. No LLM selects or ranks securities. */
@Service
class AiPickService(
    private val topMoversService: TopMoversService,
    private val investorRankClient: NaverInvestorRankClient,
    private val yahooCandles: YahooCandleClient,
    private val koreanCharts: NaverStockChartClient,
    private val screener: YahooFinanceScreenerClient,
    private val sessions: MarketSessionService,
    @Qualifier("httpFetchExecutor") private val executor: ExecutorService,
) {
    @Cacheable(cacheNames = ["ai-picks"], sync = true)
    fun getTodayPicks(): AiPicksResponse = generate(Instant.now())

    internal fun generate(now: Instant): AiPicksResponse {
        val movers = runCatching { topMoversService.fetchTopMovers(10) }.getOrNull()
        val flow = runCatching { investorRankClient.fetchFlowSnapshot(7) }.getOrNull()
        val active = runCatching { screener.fetchMostActives(8) }.getOrDefault(emptyList())
            .map { PickCandidate("US", it.ticker, it.name, it.price, it.changeRate, null) }
        val candidates = (active + buildCandidates(movers, flow))
            .filter { if (it.market == "KR") it.ticker.matches(Regex("\\d{6}")) else it.market == "US" && it.ticker.matches(Regex("[A-Z0-9][A-Z0-9.-]{0,14}")) }
            .distinctBy { "${it.market}:${it.ticker}" }
            .groupBy { it.market }.toSortedMap().values.flatMap { group ->
                (if (group.first().market == "KR") group.sortedWith(compareByDescending<PickCandidate> { !it.flowTag.isNullOrBlank() }
                    .thenBy { kotlin.math.abs(it.changeRate ?: 0.0) }.thenBy { it.ticker }) else group).take(8)
            }
        val completedSessions = sessions.upcomingSessions(now.minusSeconds(14 * 86400), 15).sessions
            .filter { Instant.parse(it.closesAt).isBefore(now.minusSeconds(1800)) }
        val futures = candidates.map { candidate ->
            CompletableFuture.supplyAsync<AiPick?>({
                val expected = completedSessions.filter { it.market == candidate.market }.maxOfOrNull { it.tradingDate }
                    ?.let(LocalDate::parse) ?: return@supplyAsync null
                val history = runCatching {
                    if (candidate.market == "US") yahooCandles.fetch(candidate.ticker.replace('.', '-'), "6mo").map {
                        PickDailyObservation(LocalDate.parse(it.date, DateTimeFormatter.BASIC_ISO_DATE), it.close, it.volume)
                    } else koreanCharts.fetchDailyBars(candidate.ticker, 60).map {
                        PickDailyObservation(LocalDate.parse(it.date, DateTimeFormatter.BASIC_ISO_DATE), it.close.toDouble(), it.volume)
                    }
                }.getOrDefault(emptyList())
                val assessment = RuleBasedPickEngine.assess(candidate, history, expected)
                AiPick(candidate.market, candidate.ticker, candidate.name,
                    assessment.reasons.firstOrNull() ?: "분석에 필요한 최근 거래 자료를 기다리고 있습니다.",
                    null, 0, assessment.blockers.joinToString(" ").ifBlank { "검토 후보이며 매수 권유나 검증된 수익 전략이 아닙니다." },
                    changeRate = null, flowTag = null, tradePlan = null, assessment = assessment)
            }, executor).completeOnTimeout(null, 12, TimeUnit.SECONDS).exceptionally { null }
        }
        val picks = futures.mapNotNull { it.join() }
            .sortedWith(compareBy<AiPick>({ it.assessment?.decision?.ordinal ?: 9 }, { it.market }, { it.ticker }))
        return AiPicksResponse(now.toString(),
            "시장별 최대 8개 후보의 완료 일봉에서 추세·거래대금·거래량·변동성을 점검합니다. 순서는 수익률 순위가 아니며, 실시간 주문 계획은 제공하지 않습니다.",
            picks)
    }

    private fun buildCandidates(
        movers: com.giwon.signaldesk.features.market.application.TopMoversResponse?,
        flow: com.giwon.signaldesk.features.market.application.InvestorFlowSnapshot?,
    ): List<PickCandidate> {
        val out = LinkedHashMap<String, PickCandidate>()
        // 급등/급락 상위 — changeRate 보유.
        // 상한가/하한가 근접(±25% 초과)은 추격매수·낙폭 리스크가 커 universe 에서 제외.
        // US 는 가격제한폭이 없지만 일중 ±25% 급변은 어차피 단기 노이즈가 커 같은 컷 적용.
        movers?.let { m ->
            val krMovers = m.kospi.gainers + m.kospi.losers + m.kosdaq.gainers + m.kosdaq.losers
            val usMovers = m.us.let { it.gainers + it.losers }
            (krMovers + usMovers).forEach { mv ->
                if (kotlin.math.abs(mv.changeRate) > 25.0) return@forEach
                out.putIfAbsent(
                    "${mv.market}:${mv.ticker}",
                    PickCandidate(mv.market, mv.ticker, mv.name, mv.price.toDouble(), mv.changeRate, null),
                )
            }
        }
        // 외인/기관 순매수 상위 — 수급 태그
        flow?.let { f ->
            fun add(items: List<com.giwon.signaldesk.features.market.application.InvestorRankItem>, tag: String) {
                items.forEach { it ->
                    val existing = out["KR:${it.ticker}"]
                    if (existing == null) {
                        out["KR:${it.ticker}"] = PickCandidate("KR", it.ticker, it.name, null, null, tag)
                    } else {
                        out["KR:${it.ticker}"] = existing.copy(flowTag = listOfNotNull(existing.flowTag, tag).distinct().joinToString(" · "))
                    }
                }
            }
            add(f.kospiForeignBuy, "외인 순매수")
            add(f.kospiInstitutionBuy, "기관 순매수")
            add(f.kosdaqForeignBuy, "코스닥 외인 순매수")
        }
        return out.values.toList()
    }

}
