package com.giwon.signaldesk.features.workspace.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.util.UUID

/**
 * user_id 스코핑 규칙:
 *  - userId == null  → user_id IS NULL 인 글로벌(레거시) 데이터만 조회/저장
 *  - userId != null  → 해당 사용자 행만 조회/저장
 *
 * 즉 사용자 데이터와 글로벌 데이터는 격리된다.
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcSignalDeskWorkspaceRepository(
    private val jdbcTemplate: JdbcTemplate,
) : SignalDeskWorkspaceRepository {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun whereUser(column: String = "user_id"): String =
        "($column = ?::uuid or ($column is null and ?::uuid is null))"

    private fun userArgs(userId: UUID?): Array<Any?> {
        val s = userId?.toString()
        return arrayOf(s, s)
    }

    // ── watchlist ───────────────────────────────────────────────────────────

    override fun loadWatchlist(userId: UUID?): List<WorkspaceWatchItem> =
        jdbcTemplate.query(
            "select id, market, ticker, name, price, change_rate, sector, stance, note from signal_desk_watchlist where ${whereUser()} order by name",
            watchlistRowMapper, *userArgs(userId),
        )

    override fun saveWatchItem(userId: UUID?, item: WorkspaceWatchItem): WorkspaceWatchItem {
        // 신규 저장(id 비어있음) 시 같은 (user_id, market, ticker) 가 이미 있으면 그 id 를 재사용해서
        // 업데이트로 처리한다. 과거에 dedupe 없이 쌓인 레거시 중복 행도 같은 id 하나로 수렴시킨다.
        val resolvedId: String = item.id.ifBlank {
            val existing = jdbcTemplate.query(
                "select id from signal_desk_watchlist where ${whereUser()} and market = ? and ticker = ? order by id limit 1",
                { rs, _ -> rs.getString("id") },
                *userArgs(userId), item.market, item.ticker,
            ).firstOrNull()
            existing ?: UUID.randomUUID().toString()
        }
        val nextItem = item.copy(id = resolvedId)
        jdbcTemplate.update(
            """
            insert into signal_desk_watchlist (id, market, ticker, name, price, change_rate, sector, stance, note, user_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)
            on conflict (id) do update set
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                price = excluded.price,
                change_rate = excluded.change_rate,
                sector = excluded.sector,
                stance = excluded.stance,
                note = excluded.note
            """.trimIndent(),
            nextItem.id, nextItem.market, nextItem.ticker, nextItem.name, nextItem.price, nextItem.changeRate, nextItem.sector, nextItem.stance, nextItem.note, userId?.toString(),
        )
        return nextItem
    }

    override fun deleteWatchItem(userId: UUID?, id: String) {
        jdbcTemplate.update("delete from signal_desk_watchlist where id = ? and ${whereUser()}", id, *userArgs(userId))
    }

    // ── portfolio ───────────────────────────────────────────────────────────

    override fun loadPortfolioPositions(userId: UUID?): List<WorkspaceHoldingPosition> =
        jdbcTemplate.query(
            "select id, market, ticker, name, buy_price, current_price, quantity, profit_amount, evaluation_amount, profit_rate from signal_desk_portfolio_positions where ${whereUser()} order by name",
            portfolioRowMapper, *userArgs(userId),
        )

    override fun savePortfolioPosition(userId: UUID?, position: WorkspaceHoldingPosition): WorkspaceHoldingPosition {
        val nextPosition = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_portfolio_positions (id, market, ticker, name, buy_price, current_price, quantity, profit_amount, evaluation_amount, profit_rate, user_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)
            on conflict (id) do update set
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                buy_price = excluded.buy_price,
                current_price = excluded.current_price,
                quantity = excluded.quantity,
                profit_amount = excluded.profit_amount,
                evaluation_amount = excluded.evaluation_amount,
                profit_rate = excluded.profit_rate
            """.trimIndent(),
            nextPosition.id, nextPosition.market, nextPosition.ticker, nextPosition.name, nextPosition.buyPrice, nextPosition.currentPrice, nextPosition.quantity, nextPosition.profitAmount, nextPosition.evaluationAmount, nextPosition.profitRate, userId?.toString(),
        )
        return nextPosition
    }

    override fun deletePortfolioPosition(userId: UUID?, id: String) {
        jdbcTemplate.update("delete from signal_desk_portfolio_positions where id = ? and ${whereUser()}", id, *userArgs(userId))
    }

    // ── paper positions ─────────────────────────────────────────────────────

    override fun loadPaperPositions(userId: UUID?): List<WorkspacePaperPosition> =
        jdbcTemplate.query(
            "select id, market, ticker, name, average_price, current_price, quantity, return_rate from signal_desk_paper_positions where ${whereUser()} order by name",
            paperPositionRowMapper, *userArgs(userId),
        )

    override fun savePaperPosition(userId: UUID?, position: WorkspacePaperPosition): WorkspacePaperPosition {
        val nextPosition = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_paper_positions (id, market, ticker, name, average_price, current_price, quantity, return_rate, user_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)
            on conflict (id) do update set
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                average_price = excluded.average_price,
                current_price = excluded.current_price,
                quantity = excluded.quantity,
                return_rate = excluded.return_rate
            """.trimIndent(),
            nextPosition.id, nextPosition.market, nextPosition.ticker, nextPosition.name, nextPosition.averagePrice, nextPosition.currentPrice, nextPosition.quantity, nextPosition.returnRate, userId?.toString(),
        )
        return nextPosition
    }

    override fun deletePaperPosition(userId: UUID?, id: String) {
        jdbcTemplate.update("delete from signal_desk_paper_positions where id = ? and ${whereUser()}", id, *userArgs(userId))
    }

    // ── paper trades ────────────────────────────────────────────────────────

    override fun loadPaperTrades(userId: UUID?): List<WorkspacePaperTrade> =
        jdbcTemplate.query(
            "select id, trade_date, side, market, ticker, name, price, quantity from signal_desk_paper_trades where ${whereUser()} order by trade_date desc, name",
            paperTradeRowMapper, *userArgs(userId),
        )

    override fun savePaperTrade(userId: UUID?, trade: WorkspacePaperTrade): WorkspacePaperTrade {
        val nextTrade = trade.copy(id = trade.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_paper_trades (id, trade_date, side, market, ticker, name, price, quantity, user_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)
            on conflict (id) do update set
                trade_date = excluded.trade_date,
                side = excluded.side,
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                price = excluded.price,
                quantity = excluded.quantity
            """.trimIndent(),
            nextTrade.id, nextTrade.tradeDate, nextTrade.side, nextTrade.market, nextTrade.ticker, nextTrade.name, nextTrade.price, nextTrade.quantity, userId?.toString(),
        )
        return nextTrade
    }

    override fun deletePaperTrade(userId: UUID?, id: String) {
        jdbcTemplate.update("delete from signal_desk_paper_trades where id = ? and ${whereUser()}", id, *userArgs(userId))
    }

    // ── ai picks ────────────────────────────────────────────────────────────

    override fun loadAiPicks(userId: UUID?): List<WorkspaceAiPick> =
        jdbcTemplate.query(
            "select id, market, ticker, name, basis, confidence, note, expected_return_rate from signal_desk_ai_picks where ${whereUser()} order by confidence desc, name",
            aiPickRowMapper, *userArgs(userId),
        )

    override fun saveAiPick(userId: UUID?, pick: WorkspaceAiPick): WorkspaceAiPick {
        val nextPick = pick.copy(id = pick.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_ai_picks (id, market, ticker, name, basis, confidence, note, expected_return_rate, user_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)
            on conflict (id) do update set
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                basis = excluded.basis,
                confidence = excluded.confidence,
                note = excluded.note,
                expected_return_rate = excluded.expected_return_rate
            """.trimIndent(),
            nextPick.id, nextPick.market, nextPick.ticker, nextPick.name, nextPick.basis, nextPick.confidence, nextPick.note, nextPick.expectedReturnRate, userId?.toString(),
        )
        return nextPick
    }

    override fun deleteAiPick(userId: UUID?, id: String) {
        jdbcTemplate.update("delete from signal_desk_ai_picks where id = ? and ${whereUser()}", id, *userArgs(userId))
    }

    // ── ai track records ────────────────────────────────────────────────────

    override fun loadAiTrackRecords(userId: UUID?): List<WorkspaceAiTrackRecord> =
        jdbcTemplate.query(
            "select id, recommended_date, market, ticker, name, entry_price, latest_price, realized_return_rate, success from signal_desk_ai_track_records where ${whereUser()} order by recommended_date desc, name",
            aiTrackRecordRowMapper, *userArgs(userId),
        )

    override fun saveAiTrackRecord(userId: UUID?, record: WorkspaceAiTrackRecord): WorkspaceAiTrackRecord {
        val nextRecord = record.copy(id = record.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_ai_track_records (id, recommended_date, market, ticker, name, entry_price, latest_price, realized_return_rate, success, user_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid)
            on conflict (id) do update set
                recommended_date = excluded.recommended_date,
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                entry_price = excluded.entry_price,
                latest_price = excluded.latest_price,
                realized_return_rate = excluded.realized_return_rate,
                success = excluded.success
            """.trimIndent(),
            nextRecord.id, nextRecord.recommendedDate, nextRecord.market, nextRecord.ticker, nextRecord.name, nextRecord.entryPrice, nextRecord.latestPrice, nextRecord.realizedReturnRate, nextRecord.success, userId?.toString(),
        )
        return nextRecord
    }

    override fun deleteAiTrackRecord(userId: UUID?, id: String) {
        jdbcTemplate.update("delete from signal_desk_ai_track_records where id = ? and ${whereUser()}", id, *userArgs(userId))
    }
}

private val watchlistRowMapper = RowMapper { rs: ResultSet, _: Int ->
    WorkspaceWatchItem(
        id = rs.getString("id"),
        market = rs.getString("market"),
        ticker = rs.getString("ticker"),
        name = rs.getString("name"),
        price = rs.getInt("price"),
        changeRate = rs.getDouble("change_rate"),
        sector = rs.getString("sector"),
        stance = rs.getString("stance"),
        note = rs.getString("note"),
    )
}

private val portfolioRowMapper = RowMapper { rs: ResultSet, _: Int ->
    WorkspaceHoldingPosition(
        id = rs.getString("id"),
        market = rs.getString("market"),
        ticker = rs.getString("ticker"),
        name = rs.getString("name"),
        buyPrice = rs.getInt("buy_price"),
        currentPrice = rs.getInt("current_price"),
        quantity = rs.getInt("quantity"),
        profitAmount = rs.getLong("profit_amount"),
        evaluationAmount = rs.getLong("evaluation_amount"),
        profitRate = rs.getDouble("profit_rate"),
    )
}

private val paperPositionRowMapper = RowMapper { rs: ResultSet, _: Int ->
    WorkspacePaperPosition(
        id = rs.getString("id"),
        market = rs.getString("market"),
        ticker = rs.getString("ticker"),
        name = rs.getString("name"),
        averagePrice = rs.getInt("average_price"),
        currentPrice = rs.getInt("current_price"),
        quantity = rs.getInt("quantity"),
        returnRate = rs.getDouble("return_rate"),
    )
}

private val paperTradeRowMapper = RowMapper { rs: ResultSet, _: Int ->
    WorkspacePaperTrade(
        id = rs.getString("id"),
        tradeDate = rs.getString("trade_date"),
        side = rs.getString("side"),
        market = rs.getString("market"),
        ticker = rs.getString("ticker"),
        name = rs.getString("name"),
        price = rs.getInt("price"),
        quantity = rs.getInt("quantity"),
    )
}

private val aiPickRowMapper = RowMapper { rs: ResultSet, _: Int ->
    WorkspaceAiPick(
        id = rs.getString("id"),
        market = rs.getString("market"),
        ticker = rs.getString("ticker"),
        name = rs.getString("name"),
        basis = rs.getString("basis"),
        confidence = rs.getInt("confidence"),
        note = rs.getString("note"),
        expectedReturnRate = rs.getDouble("expected_return_rate"),
    )
}

private val aiTrackRecordRowMapper = RowMapper { rs: ResultSet, _: Int ->
    WorkspaceAiTrackRecord(
        id = rs.getString("id"),
        recommendedDate = rs.getString("recommended_date"),
        market = rs.getString("market"),
        ticker = rs.getString("ticker"),
        name = rs.getString("name"),
        entryPrice = rs.getInt("entry_price"),
        latestPrice = rs.getInt("latest_price"),
        realizedReturnRate = rs.getDouble("realized_return_rate"),
        success = rs.getBoolean("success"),
    )
}
