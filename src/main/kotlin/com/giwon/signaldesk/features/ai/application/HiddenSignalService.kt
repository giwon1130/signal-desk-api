package com.giwon.signaldesk.features.ai.application

import com.giwon.signaldesk.features.disclosure.application.DisclosureImportance
import com.giwon.signaldesk.features.disclosure.application.DisclosureSeenRepository
import com.giwon.signaldesk.features.market.application.NaverInvestorRankClient
import com.giwon.signaldesk.features.market.application.TopMover
import com.giwon.signaldesk.features.market.application.TopMoversResponse
import com.giwon.signaldesk.features.market.application.TopMoversService
import com.giwon.signaldesk.features.workspace.application.UserWatchTickerRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 숨은 시그널 — 사용자의 보유/관심 종목 중 실제로 신호가 잡힌 종목.
 *
 * KR: (DART 공시 ∨ 외인·기관 순매수 ∨ KR 급등락)
 * US: (SEC EDGAR 8-K 공시 ∨ Yahoo 급등락)
 *
 * Gemini 없이 실데이터 조합만으로 "내 종목에 지금 뭔가 일어나고 있다"를 잡아낸다.
 * 트리거가 하나도 없는 종목은 결과에서 제외 — 진짜 신호만 남긴다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class HiddenSignalService(
    private val jdbc: JdbcTemplate,
    private val userWatchTickers: UserWatchTickerRepository,
    private val disclosureSeenRepository: DisclosureSeenRepository,
    private val investorRankClient: NaverInvestorRankClient,
    private val topMoversService: TopMoversService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun signalsForUser(userId: UUID): HiddenSignalsResponse {
        // 사용자의 보유/관심 ticker → 종목명 (KR=6자리 검증, US=대문자 정규화).
        val krNameByTicker = userWatchTickers.namedTickersForUser(userId, market = "KR")
        val usNameByTicker = userWatchTickers.namedTickersForUser(userId, market = "US")
        if (krNameByTicker.isEmpty() && usNameByTicker.isEmpty()) {
            return HiddenSignalsResponse(Instant.now().toString(), emptyList())
        }

        // 두 시장 공통으로 한 번만 가져옴.
        val movers = runCatching { topMoversService.fetchTopMovers(20) }.getOrNull()

        val krSignals = if (krNameByTicker.isNotEmpty()) buildKrSignals(krNameByTicker, movers) else emptyList()
        val usSignals = if (usNameByTicker.isNotEmpty()) buildUsSignals(usNameByTicker, movers) else emptyList()
        val sorted = (krSignals + usSignals).sortedByDescending { it.triggers.size }

        log.info(
            "HiddenSignals — krTickers={} usTickers={} signals={}",
            krNameByTicker.size, usNameByTicker.size, sorted.size,
        )
        return HiddenSignalsResponse(Instant.now().toString(), sorted)
    }

    private fun buildKrSignals(
        nameByTicker: Map<String, String>,
        movers: TopMoversResponse?,
    ): List<HiddenSignal> {
        val tickers = nameByTicker.keys
        val disclosuresByTicker = disclosureSeenRepository.findRecentByStockCodes(tickers, limit = 100)
            .groupBy { it.stockCode }
        val flow = runCatching { investorRankClient.fetchFlowSnapshot(10) }.getOrNull()
        val moverByTicker: Map<String, TopMover> = movers?.let {
            (it.kospi.gainers + it.kospi.losers + it.kosdaq.gainers + it.kosdaq.losers)
                .associateBy { m -> m.ticker }
        } ?: emptyMap()

        return tickers.mapNotNull { ticker ->
            val triggers = buildList {
                disclosuresByTicker[ticker]?.filter { it.importance != DisclosureImportance.LOW }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ds -> add(SignalTrigger("DISCLOSURE", "공시 ${ds.size}건", ds.first().reportNm)) }
                flow?.kospiForeignBuy?.firstOrNull { it.ticker == ticker }?.let {
                    add(SignalTrigger("FOREIGN_BUY", "외인 순매수 ${it.rank}위", null))
                }
                flow?.kosdaqForeignBuy?.firstOrNull { it.ticker == ticker }?.let {
                    add(SignalTrigger("FOREIGN_BUY", "코스닥 외인 순매수 ${it.rank}위", null))
                }
                flow?.kospiInstitutionBuy?.firstOrNull { it.ticker == ticker }?.let {
                    add(SignalTrigger("INSTITUTION_BUY", "기관 순매수 ${it.rank}위", null))
                }
                moverByTicker[ticker]?.let { mv ->
                    val type = if (mv.changeRate >= 0) "SURGE" else "PLUNGE"
                    add(SignalTrigger(type, "%+.2f%%".format(mv.changeRate), null))
                }
            }
            if (triggers.isEmpty()) null
            else HiddenSignal("KR", ticker, nameByTicker[ticker] ?: ticker, triggers)
        }
    }

    private fun buildUsSignals(
        nameByTicker: Map<String, String>,
        movers: TopMoversResponse?,
    ): List<HiddenSignal> {
        val tickers = nameByTicker.keys
        val disclosuresByTicker = loadRecentUsDisclosures(tickers).groupBy { it.ticker }
        val moverByTicker: Map<String, TopMover> = movers?.us?.let {
            (it.gainers + it.losers).associateBy { m -> m.ticker.uppercase() }
        } ?: emptyMap()

        return tickers.mapNotNull { ticker ->
            val triggers = buildList {
                disclosuresByTicker[ticker]?.let { ds ->
                    add(SignalTrigger("DISCLOSURE", "SEC 공시 ${ds.size}건", ds.first().formType))
                }
                moverByTicker[ticker]?.let { mv ->
                    val type = if (mv.changeRate >= 0) "SURGE" else "PLUNGE"
                    add(SignalTrigger(type, "%+.2f%%".format(mv.changeRate), null))
                }
            }
            if (triggers.isEmpty()) null
            else HiddenSignal("US", ticker, nameByTicker[ticker] ?: ticker, triggers)
        }
    }

    /** US 사용자 종목의 최근 7일 SEC EDGAR 공시. (ticker, form_type) 페어. */
    private fun loadRecentUsDisclosures(tickers: Set<String>): List<UsDisclosureHit> {
        if (tickers.isEmpty()) return emptyList()
        val placeholders = tickers.joinToString(",") { "?" }
        return jdbc.query(
            """
            select ticker, form_type from signal_desk_us_disclosure_seen
            where ticker in ($placeholders)
              and seen_at > now() - interval '7 days'
            order by seen_at desc
            """.trimIndent(),
            { rs, _ -> UsDisclosureHit(rs.getString("ticker"), rs.getString("form_type")) },
            *tickers.toTypedArray(),
        )
    }

    private data class UsDisclosureHit(val ticker: String, val formType: String)
}
