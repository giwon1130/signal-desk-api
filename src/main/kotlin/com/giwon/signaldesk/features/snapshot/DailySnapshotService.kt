package com.giwon.signaldesk.features.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.ai.application.AiPickService
import com.giwon.signaldesk.features.ai.application.PickDecision
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Market-local closing reference archive, not execution prices or a reconstructible trading ledger. */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DailySnapshotService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val aiPickService: AiPickService,
    private val policy: SnapshotSessionPolicy,
    private val prices: SnapshotClosePrices,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // Existing admin endpoint response shape is unchanged; counts mean newly inserted rows.
    data class Result(val marketSaved: Boolean, val aiPicksSaved: Int, val portfolioRows: Int)

    fun runDailySnapshot(market: String = "KR"): Result {
        val now = clock.instant()
        val session = policy.target(market, now) ?: run {
            log.info("snapshot skipped market={} reason=OUTSIDE_CAPTURE_WINDOW_OR_UNVERIFIED_CALENDAR", market)
            return Result(false, 0, 0)
        }
        // Bound each external symbol to one attempt per run, including failures.
        val quotes = mutableMapOf<String, SnapshotClose?>()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        fun close(ticker: String): SnapshotClose? {
            if (quotes.containsKey(ticker)) return quotes[ticker]
            if (quotes.size >= 64 || System.nanoTime() >= deadline) return null
            return prices.stock(ticker, session).also { quotes[ticker] = it }
        }
        val saved = runCatching { snapshotMarket(session, now) }
            .onFailure { log.warn("snapshot market failed market={} type={}", market, it.javaClass.simpleName) }.getOrDefault(false)
        val picks = runCatching { snapshotAiPicks(session, ::close) }
            .onFailure { log.warn("snapshot picks failed market={} type={}", market, it.javaClass.simpleName) }.getOrDefault(0)
        val portfolios = runCatching { snapshotPortfolios(session, ::close) }
            .onFailure { log.warn("snapshot portfolios failed market={} type={}", market, it.javaClass.simpleName) }.getOrDefault(0)
        log.info("snapshot complete market={} tradingDate={} marketSaved={} aiPicks={} portfolioRows={}", market, session.date, saved, picks, portfolios)
        return Result(saved, picks, portfolios)
    }

    private fun snapshotMarket(session: SnapshotSession, now: Instant): Boolean {
        val indices = prices.indices(session)
        if (indices.size != 2) {
            log.info("snapshot market skipped market={} tradingDate={} reason=MISSING_DATED_CLOSE available={}/2", session.market, session.date, indices.size)
            return false
        }
        return jdbc.update("""
            insert into signal_desk_market_close_snapshot (market, trading_date, session_closes_at, captured_at, indices)
            values (?, ?, ?, ?, ?::jsonb) on conflict (market, trading_date) do nothing
        """.trimIndent(), session.market, Date.valueOf(session.date), Timestamp.from(session.closesAt), Timestamp.from(now),
            objectMapper.writeValueAsString(indices)) > 0
    }

    private fun snapshotAiPicks(session: SnapshotSession, close: (String) -> SnapshotClose?): Int {
        val response = aiPickService.getTodayPicks()
        val now = clock.instant()
        val generated = runCatching { Instant.parse(response.generatedAt) }.getOrNull() ?: return 0
        if (generated < session.closesAt || generated > now || generated < now.minusSeconds(3600)) return 0
        var saved = 0
        for (p in response.picks.filter { it.market == session.market && it.assessment?.decision == PickDecision.REVIEW &&
            it.assessment.analysisDate == session.date.toString() }) {
            val quote = close(p.ticker) ?: continue
            saved += jdbc.update("""
                insert into signal_desk_ai_pick_history
                    (id, pick_date, market, ticker, name, reason, confidence, expected_return_rate, price_at_pick,
                     analysis_date, generated_at, rules_version, reference_close, price_basis, currency, source, assessment)
                values (?::uuid, ?, ?, ?, ?, ?, 0, null, null, ?, ?, ?, ?, 'SESSION_CLOSE_REFERENCE_ONLY', ?, ?, ?::jsonb)
                on conflict (pick_date, market, ticker) do nothing
            """.trimIndent(), UUID.randomUUID().toString(), Date.valueOf(session.date), p.market, p.ticker, p.name, p.reason,
                Date.valueOf(session.date), Timestamp.from(generated), p.assessment!!.rulesVersion, quote.price, session.currency, quote.source,
                objectMapper.writeValueAsString(p.assessment))
        }
        return saved
    }

    private fun snapshotPortfolios(session: SnapshotSession, close: (String) -> SnapshotClose?): Int {
        val now = clock.instant()
        val positions = jdbc.query("""
            select p.user_id, p.ticker, p.quantity, p.buy_price from signal_desk_portfolio_positions p
            where p.user_id is not null and p.market = ?
              and not exists (select 1 from signal_desk_daily_portfolio_snapshot s
                  where s.user_id = p.user_id and s.market = p.market and s.snapshot_date = ?)
            order by p.user_id, p.ticker
        """.trimIndent(), { rs, _ -> SnapshotPosition(UUID.fromString(rs.getString("user_id")), rs.getString("ticker"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("buy_price")) }, session.market, Date.valueOf(session.date))
        var saved = 0
        var skipped = 0
        positions.groupBy { it.userId }.forEach { (userId, group) ->
            val valuation = valueSnapshotPositions(group, close)
            if (valuation == null) { skipped++; return@forEach }
            // Holdings observed now, not reconstructed at session close. Capture that distinction explicitly.
            saved += jdbc.update("""
                insert into signal_desk_daily_portfolio_snapshot
                    (user_id, snapshot_date, market, evaluation_amount, cost_amount, profit_amount, position_count,
                     currency, price_basis, session_closes_at, holdings_observed_at, price_sources)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, 'CAPTURED_HOLDINGS_AT_SESSION_CLOSE_PRICES', ?, ?, ?::jsonb)
                on conflict (user_id, snapshot_date, market) do nothing
            """.trimIndent(), userId.toString(), Date.valueOf(session.date), session.market, valuation.evaluation, valuation.cost,
                valuation.evaluation - valuation.cost, group.size, session.currency, Timestamp.from(session.closesAt), Timestamp.from(now),
                objectMapper.writeValueAsString(valuation.sources))
        }
        if (skipped > 0) log.info("snapshot portfolios skipped market={} tradingDate={} groups={} reason=INCOMPLETE_OR_INVALID_PRICES", session.market, session.date, skipped)
        return saved
    }
}

internal data class SnapshotPosition(val userId: UUID, val ticker: String, val quantity: BigDecimal?, val buyPrice: BigDecimal?)
internal data class SnapshotValuation(val evaluation: BigDecimal, val cost: BigDecimal, val sources: Set<String>)

internal fun valueSnapshotPositions(positions: List<SnapshotPosition>, close: (String) -> SnapshotClose?): SnapshotValuation? {
    if (positions.isEmpty()) return null
    var evaluation = BigDecimal.ZERO
    var cost = BigDecimal.ZERO
    val sources = mutableSetOf<String>()
    for (p in positions) {
        val quantity = p.quantity?.takeIf { it > BigDecimal.ZERO } ?: return null
        val buy = p.buyPrice?.takeIf { it > BigDecimal.ZERO } ?: return null
        val quote = close(p.ticker)?.takeIf { it.price > BigDecimal.ZERO } ?: return null
        evaluation += quote.price * quantity
        cost += buy * quantity
        sources += quote.source
    }
    return SnapshotValuation(evaluation, cost, sources)
}
