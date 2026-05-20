package com.giwon.signaldesk.features.ai.application

import com.giwon.signaldesk.features.disclosure.application.DisclosureSeenRepository
import com.giwon.signaldesk.features.market.application.NaverInvestorRankClient
import com.giwon.signaldesk.features.market.application.TopMoversService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * 숨은 시그널 — 사용자의 보유/관심 KR 종목 ∩ (DART 공시 ∨ 외인·기관 순매수 ∨ 급등락).
 *
 * Gemini 없이 실데이터 조합만으로 "내 종목에 지금 뭔가 일어나고 있다"를 잡아낸다.
 * 트리거가 하나도 없는 종목은 결과에서 제외 — 진짜 신호만 남긴다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class HiddenSignalService(
    private val jdbc: JdbcTemplate,
    private val disclosureSeenRepository: DisclosureSeenRepository,
    private val investorRankClient: NaverInvestorRankClient,
    private val topMoversService: TopMoversService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun signalsForUser(userId: UUID): HiddenSignalsResponse {
        val nameByTicker = loadUserKrTickers(userId)
        if (nameByTicker.isEmpty()) {
            return HiddenSignalsResponse(Instant.now().toString(), emptyList())
        }
        val tickers = nameByTicker.keys

        val disclosuresByTicker = disclosureSeenRepository.findRecentByStockCodes(tickers, limit = 100)
            .groupBy { it.stockCode }
        val flow = runCatching { investorRankClient.fetchFlowSnapshot(10) }.getOrNull()
        val movers = runCatching { topMoversService.fetchTopMovers(20) }.getOrNull()
        val moverByTicker = movers?.let {
            (it.kospi.gainers + it.kospi.losers + it.kosdaq.gainers + it.kosdaq.losers)
                .associateBy { m -> m.ticker }
        } ?: emptyMap()

        val signals = tickers.mapNotNull { ticker ->
            val triggers = buildList {
                disclosuresByTicker[ticker]?.let { ds ->
                    add(SignalTrigger("DISCLOSURE", "공시 ${ds.size}건", ds.first().reportNm))
                }
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
        // 트리거 많은 종목 우선
        val sorted = signals.sortedByDescending { it.triggers.size }
        log.info("HiddenSignals — user tickers={}, signals={}", tickers.size, sorted.size)
        return HiddenSignalsResponse(Instant.now().toString(), sorted)
    }

    /** 사용자의 KR watchlist + portfolio — ticker(6자리 숫자) → 종목명. */
    private fun loadUserKrTickers(userId: UUID): Map<String, String> {
        val rows = jdbc.query(
            """
            select ticker, name from signal_desk_watchlist where user_id = ?::uuid and market = 'KR'
            union
            select ticker, name from signal_desk_portfolio_positions where user_id = ?::uuid and market = 'KR'
            """.trimIndent(),
            { rs, _ -> rs.getString("ticker") to rs.getString("name") },
            userId.toString(), userId.toString(),
        )
        return rows
            .filter { it.first.length == 6 && it.first.all(Char::isDigit) }
            .associate { it.first to it.second }
    }
}
