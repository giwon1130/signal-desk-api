package com.giwon.signaldesk.features.ai.application

import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
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
 * 종목 universe: 급등/급락 상위(TopMovers) + 외인·기관 순매수 상위.
 * "오늘 시장이 실제로 주목하는" 풀 안에서만 Gemini 가 고르게 해 환각을 방지한다.
 *
 * 캐시 TTL 30분 (ai-picks). 장중 시세 변동을 어느 정도 따라가되 Gemini 호출 비용은 절감.
 */
@Service
class AiPickService(
    private val topMoversService: TopMoversService,
    private val investorRankClient: NaverInvestorRankClient,
    private val newsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
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

        val candidates = buildCandidates(movers, flow)
        if (candidates.isEmpty()) {
            log.warn("AiPickService skipped — 후보 종목 없음")
            return null
        }

        val analysis = runCatching { geminiClient.summarizeAiPicks(candidates, headlines) }
            .getOrElse { log.warn("AiPick Gemini call failed", it); null }
            ?: return null

        // Gemini 가 universe 밖 종목을 환각으로 만들면 제거.
        // ticker 매칭 시: leading zero 손실 보정(017900→17900) + 종목명 fallback.
        val byTicker = candidates.associateBy { it.ticker }
        val byName = candidates.associateBy { it.name.trim() }
        val picks = analysis.picks.mapNotNull { p ->
            val raw = p.ticker.trim()
            val c = byTicker[raw]
                ?: byTicker[raw.padStart(6, '0')]
                ?: byName[raw]
                ?: byName[p.name.trim()]
                ?: return@mapNotNull null
            p.copy(market = c.market, ticker = c.ticker, name = c.name)
        }
        if (picks.isEmpty()) {
            log.warn(
                "AiPick — 매칭된 픽 0. geminiPicks(ticker/name)={}, candidate ticker 샘플={}",
                analysis.picks.map { "${it.ticker}/${it.name}" },
                candidates.take(12).map { it.ticker },
            )
            return null
        }
        log.info("AiPicks generated. candidates={}, picks={}", candidates.size, picks.size)
        return AiPicksResponse(generatedAt = Instant.now().toString(), summary = analysis.summary, picks = picks)
    }

    private fun buildCandidates(
        movers: com.giwon.signaldesk.features.market.application.TopMoversResponse?,
        flow: com.giwon.signaldesk.features.market.application.InvestorFlowSnapshot?,
    ): List<PickCandidate> {
        val out = LinkedHashMap<String, PickCandidate>()
        // 급등/급락 상위 — changeRate 보유.
        // 상한가/하한가 근접(±25% 초과)은 추격매수·낙폭 리스크가 커 universe 에서 제외.
        movers?.let { m ->
            (m.kospi.gainers + m.kospi.losers + m.kosdaq.gainers + m.kosdaq.losers).forEach { mv ->
                if (kotlin.math.abs(mv.changeRate) > 25.0) return@forEach
                out.putIfAbsent(mv.ticker, PickCandidate(mv.market, mv.ticker, mv.name, mv.changeRate, null))
            }
        }
        // 외인/기관 순매수 상위 — 수급 태그
        flow?.let { f ->
            fun add(items: List<com.giwon.signaldesk.features.market.application.InvestorRankItem>, tag: String) {
                items.forEach { it ->
                    val existing = out[it.ticker]
                    if (existing == null) {
                        out[it.ticker] = PickCandidate("KR", it.ticker, it.name, null, tag)
                    } else if (existing.flowTag == null) {
                        out[it.ticker] = existing.copy(flowTag = tag)
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
