package com.giwon.signaldesk.features.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.common.KST
import com.giwon.signaldesk.features.ai.application.AiPickService
import com.giwon.signaldesk.features.market.application.MarketOverviewService
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * 일별 데이터 적재 — 휘발되던 시장/AI/포트폴리오 상태를 하루 1회 박제.
 * 지금 화면에 쓰진 않지만, 적중률 리포트·자산 추이·지표 백테스트의 원천 데이터가 된다.
 * 모든 쓰기는 (날짜) 기준 upsert — 재실행해도 그날 데이터를 덮을 뿐 중복이 없다(멱등).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DailySnapshotService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val marketOverviewService: MarketOverviewService,
    private val aiPickService: AiPickService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(val marketSaved: Boolean, val aiPicksSaved: Int, val portfolioRows: Int)

    fun runDailySnapshot(): Result {
        val today = LocalDate.now(KST)
        val market = runCatching { snapshotMarket(today) }
            .onFailure { log.warn("daily market snapshot failed", it) }.getOrDefault(false)
        val picks = runCatching { snapshotAiPicks(today) }
            .onFailure { log.warn("daily ai-pick snapshot failed", it) }.getOrDefault(0)
        val portfolio = runCatching { snapshotPortfolios(today) }
            .onFailure { log.warn("daily portfolio snapshot failed", it) }.getOrDefault(0)
        log.info("daily snapshot — date={} market={} aiPicks={} portfolioRows={}", today, market, picks, portfolio)
        return Result(market, picks, portfolio)
    }

    // ── 1) 시장 스냅샷 ──────────────────────────────────────────────────────
    private fun snapshotMarket(date: LocalDate): Boolean {
        val summary = marketOverviewService.getSummary()
        val sections = marketOverviewService.getMarketSections()
        val idx = (sections.koreaMarket.indices + sections.usMarket.indices).associateBy { it.label.uppercase() }
        fun v(label: String) = idx[label]?.value
        fun c(label: String) = idx[label]?.changeRate

        val metricsJson = objectMapper.writeValueAsString(
            mapOf(
                "marketSummary" to summary.marketSummary.map { mapOf("label" to it.label, "score" to it.score, "state" to it.state) },
                "riskKr" to mapOf("score" to summary.compositeRiskKr.score, "score100" to summary.compositeRiskKr.score100, "level" to summary.compositeRiskKr.level),
                "riskUs" to mapOf("score" to summary.compositeRiskUs.score, "score100" to summary.compositeRiskUs.score100, "level" to summary.compositeRiskUs.level),
            )
        )
        jdbc.update(
            """
            insert into signal_desk_daily_market_snapshot
                (snapshot_date, kospi, kospi_change, kosdaq, kosdaq_change, nasdaq, nasdaq_change, sp500, sp500_change, risk_score_kr, risk_score_us, metrics)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            on conflict (snapshot_date) do update set
                kospi = excluded.kospi, kospi_change = excluded.kospi_change,
                kosdaq = excluded.kosdaq, kosdaq_change = excluded.kosdaq_change,
                nasdaq = excluded.nasdaq, nasdaq_change = excluded.nasdaq_change,
                sp500 = excluded.sp500, sp500_change = excluded.sp500_change,
                risk_score_kr = excluded.risk_score_kr, risk_score_us = excluded.risk_score_us,
                metrics = excluded.metrics, created_at = now()
            """.trimIndent(),
            java.sql.Date.valueOf(date),
            v("KOSPI"), c("KOSPI"), v("KOSDAQ"), c("KOSDAQ"),
            v("NASDAQ"), c("NASDAQ"), v("S&P 500"), c("S&P 500"),
            summary.compositeRiskKr.score, summary.compositeRiskUs.score, metricsJson,
        )
        return true
    }

    // ── 2) AI 픽 이력 ───────────────────────────────────────────────────────
    private fun snapshotAiPicks(date: LocalDate): Int {
        val picks = aiPickService.getTodayPicks()?.picks.orEmpty()
        if (picks.isEmpty()) return 0
        // 적중률 판정 기준가 — 스냅샷 시점 현재가(센트 보존)를 같이 박제.
        val krTickers = picks.filter { it.market == "KR" }.map { it.ticker }
        val usTickers = picks.filter { it.market == "US" }.map { it.ticker }
        val krPrice = if (krTickers.isNotEmpty()) runCatching { krQuotes.fetchKoreanQuotes(krTickers) }.getOrDefault(emptyMap()) else emptyMap()
        val usPrice = if (usTickers.isNotEmpty()) runCatching { usQuotes.fetchUsQuotes(usTickers) }.getOrDefault(emptyMap()) else emptyMap()

        var saved = 0
        for (p in picks) {
            val quote = (if (p.market == "KR") krPrice else usPrice)[p.ticker]
            jdbc.update(
                """
                insert into signal_desk_ai_pick_history
                    (id, pick_date, market, ticker, name, reason, confidence, expected_return_rate, price_at_pick, change_rate_at_pick)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (pick_date, market, ticker) do update set
                    name = excluded.name, reason = excluded.reason, confidence = excluded.confidence,
                    expected_return_rate = excluded.expected_return_rate,
                    price_at_pick = coalesce(excluded.price_at_pick, signal_desk_ai_pick_history.price_at_pick),
                    change_rate_at_pick = excluded.change_rate_at_pick
                """.trimIndent(),
                UUID.randomUUID().toString(), java.sql.Date.valueOf(date),
                p.market, p.ticker, p.name, p.reason, p.confidence, p.expectedReturnRate,
                quote?.exactPrice?.takeIf { it > 0 }, p.changeRate,
            )
            saved++
        }
        return saved
    }

    // ── 3) 포트폴리오 일별 평가액 ───────────────────────────────────────────
    private fun snapshotPortfolios(date: LocalDate): Int {
        data class Pos(val userId: UUID, val market: String, val ticker: String, val qty: Int, val buyPrice: Double, val storedPrice: Double)
        val positions = jdbc.query(
            "select user_id, market, ticker, quantity, buy_price, current_price from signal_desk_portfolio_positions where user_id is not null",
        ) { rs, _ ->
            Pos(
                userId = UUID.fromString(rs.getString("user_id")),
                market = rs.getString("market"), ticker = rs.getString("ticker"),
                qty = rs.getInt("quantity"), buyPrice = rs.getDouble("buy_price"),
                storedPrice = rs.getDouble("current_price"),
            )
        }
        if (positions.isEmpty()) return 0

        // 시장별 일괄 시세 — 미보유 유저가 봐도 안 갱신되는 stored current_price 대신 라이브 가격 우선.
        val krTickers = positions.filter { it.market == "KR" }.map { it.ticker }.distinct()
        val usTickers = positions.filter { it.market == "US" }.map { it.ticker }.distinct()
        val krPrice = if (krTickers.isNotEmpty()) runCatching { krQuotes.fetchKoreanQuotes(krTickers) }.getOrDefault(emptyMap()) else emptyMap()
        val usPrice = if (usTickers.isNotEmpty()) runCatching { usQuotes.fetchUsQuotes(usTickers) }.getOrDefault(emptyMap()) else emptyMap()

        var rows = 0
        positions.groupBy { it.userId to it.market }.forEach { (key, group) ->
            val (userId, market) = key
            var evaluation = 0.0
            var cost = 0.0
            for (p in group) {
                val live = (if (p.market == "KR") krPrice else usPrice)[p.ticker]?.exactPrice?.takeIf { it > 0 }
                evaluation += (live ?: p.storedPrice) * p.qty
                cost += p.buyPrice * p.qty
            }
            jdbc.update(
                """
                insert into signal_desk_daily_portfolio_snapshot
                    (user_id, snapshot_date, market, evaluation_amount, cost_amount, profit_amount, position_count)
                values (?::uuid, ?, ?, ?, ?, ?, ?)
                on conflict (user_id, snapshot_date, market) do update set
                    evaluation_amount = excluded.evaluation_amount, cost_amount = excluded.cost_amount,
                    profit_amount = excluded.profit_amount, position_count = excluded.position_count,
                    created_at = now()
                """.trimIndent(),
                userId.toString(), java.sql.Date.valueOf(date), market,
                evaluation, cost, evaluation - cost, group.size,
            )
            rows++
        }
        return rows
    }
}
