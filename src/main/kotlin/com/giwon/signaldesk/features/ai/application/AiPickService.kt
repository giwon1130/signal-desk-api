package com.giwon.signaldesk.features.ai.application

import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverInvestorRankClient
import com.giwon.signaldesk.features.market.application.TopMoversService
import com.giwon.signaldesk.features.media.application.GeminiClient
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 오늘의 AI 픽 — Gemini 가 단타 관점에서 종목을 추천.
 *
 * 종목 universe:
 *  - KR: TopMovers(kospi/kosdaq gainers/losers) + 외인·기관 순매수 상위
 *  - US: TopMovers(Yahoo most actives/gainers/losers)
 * "오늘 시장이 실제로 주목하는" 풀 안에서만 Gemini 가 고르게 해 환각을 방지한다.
 * App 단에서 사용자 marketPreference 에 따라 KR/US 픽이 필터링됨.
 *
 * 캐시 TTL 30분 (ai-picks). 장중 시세 변동을 어느 정도 따라가되 Gemini 호출 비용은 절감.
 */
@Service
class AiPickService(
    private val topMoversService: TopMoversService,
    private val investorRankClient: NaverInvestorRankClient,
    private val newsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
    private val pickAssembler: AiPickAssembler,
    private val quoteClient: NaverFinanceQuoteClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable(cacheNames = ["ai-picks"], unless = "#result == null")
    fun getTodayPicks(): AiPicksResponse? {
        if (!geminiClient.isEnabled()) {
            log.info("AiPickService skipped — Gemini 미설정")
            return null
        }

        val movers = runCatching { topMoversService.fetchTopMovers(10) }.getOrNull()
        val flow = runCatching { investorRankClient.fetchFlowSnapshot(7) }.getOrNull()
        val headlines = runCatching { newsRssClient.fetchMarketNews() }.getOrNull() ?: emptyList()

        val candidates = enrichMissingPrices(buildCandidates(movers, flow))
        if (candidates.isEmpty()) {
            log.warn("AiPickService skipped — 후보 종목 없음")
            return null
        }

        val analysis = runCatching { geminiClient.summarizeAiPicks(candidates, headlines) }
            .getOrElse { log.warn("AiPick Gemini call failed", it); null }
            ?: return null

        val generatedAt = Instant.now()
        val picks = pickAssembler.assemble(analysis.picks, candidates, generatedAt)
        if (picks.isEmpty()) {
            log.warn(
                "AiPick — 매칭된 픽 0. geminiPicks(ticker/name)={}, candidate ticker 샘플={}",
                analysis.picks.map { "${it.ticker}/${it.name}" },
                candidates.take(12).map { it.ticker },
            )
            return AiPicksResponse(generatedAt.toString(), "지금은 검토 근거가 충분한 후보가 없어", emptyList())
        }
        log.info("AiPicks generated. candidates={}, picks={}", candidates.size, picks.size)
        return AiPicksResponse(generatedAt = generatedAt.toString(), summary = analysis.summary, picks = picks)
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
                    mv.ticker,
                    PickCandidate(mv.market, mv.ticker, mv.name, mv.price.toDouble(), mv.changeRate, null),
                )
            }
        }
        // 외인/기관 순매수 상위 — 수급 태그
        flow?.let { f ->
            fun add(items: List<com.giwon.signaldesk.features.market.application.InvestorRankItem>, tag: String) {
                items.forEach { it ->
                    val existing = out[it.ticker]
                    if (existing == null) {
                        out[it.ticker] = PickCandidate("KR", it.ticker, it.name, null, null, tag)
                    } else {
                        out[it.ticker] = existing.copy(flowTag = listOfNotNull(existing.flowTag, tag).distinct().joinToString(" · "))
                    }
                }
            }
            add(f.kospiForeignBuy, "외인 순매수")
            add(f.kospiInstitutionBuy, "기관 순매수")
            add(f.kosdaqForeignBuy, "코스닥 외인 순매수")
        }
        return out.values.toList()
    }

    /** 수급만으로 들어온 한국 후보도 계획 가격을 만들 수 있도록 짧은 시세를 보강한다. */
    private fun enrichMissingPrices(candidates: List<PickCandidate>): List<PickCandidate> {
        val missingKrTickers = candidates
            .filter { it.market == "KR" && it.price == null }
            .map { it.ticker }
        if (missingKrTickers.isEmpty()) return candidates

        val quotes = runCatching { quoteClient.fetchKoreanQuotes(missingKrTickers) }
            .getOrElse {
                log.warn("AiPick quote enrichment failed. tickers={}", missingKrTickers, it)
                emptyMap()
            }
        return candidates.map { candidate ->
            val quote = quotes[candidate.ticker]
            if (candidate.price == null && quote != null) {
                candidate.copy(price = quote.exactPrice, changeRate = candidate.changeRate ?: quote.changeRate)
            } else candidate
        }
    }
}
