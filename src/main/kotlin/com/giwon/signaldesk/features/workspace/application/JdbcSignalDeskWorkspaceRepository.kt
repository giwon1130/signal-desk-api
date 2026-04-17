package com.giwon.signaldesk.features.workspace.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcSignalDeskWorkspaceRepository(
    private val jdbcTemplate: JdbcTemplate,
) : SignalDeskWorkspaceRepository {

    override fun loadWatchlist(): List<WorkspaceWatchItem> =
        jdbcTemplate.query(
            "select id, market, ticker, name, price, change_rate, sector, stance, note from signal_desk_watchlist order by name",
            watchlistRowMapper,
        )

    override fun saveWatchItem(item: WorkspaceWatchItem): WorkspaceWatchItem {
        val nextItem = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_watchlist (id, market, ticker, name, price, change_rate, sector, stance, note)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            nextItem.id, nextItem.market, nextItem.ticker, nextItem.name, nextItem.price, nextItem.changeRate, nextItem.sector, nextItem.stance, nextItem.note,
        )
        return nextItem
    }

    override fun deleteWatchItem(id: String) {
        jdbcTemplate.update("delete from signal_desk_watchlist where id = ?", id)
    }

    override fun loadPortfolioPositions(): List<WorkspaceHoldingPosition> =
        jdbcTemplate.query(
            "select id, market, ticker, name, buy_price, current_price, quantity, profit_amount, evaluation_amount, profit_rate from signal_desk_portfolio_positions order by name",
            portfolioRowMapper,
        )

    override fun savePortfolioPosition(position: WorkspaceHoldingPosition): WorkspaceHoldingPosition {
        val nextPosition = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_portfolio_positions (id, market, ticker, name, buy_price, current_price, quantity, profit_amount, evaluation_amount, profit_rate)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            nextPosition.id, nextPosition.market, nextPosition.ticker, nextPosition.name, nextPosition.buyPrice, nextPosition.currentPrice, nextPosition.quantity, nextPosition.profitAmount, nextPosition.evaluationAmount, nextPosition.profitRate,
        )
        return nextPosition
    }

    override fun deletePortfolioPosition(id: String) {
        jdbcTemplate.update("delete from signal_desk_portfolio_positions where id = ?", id)
    }

    override fun loadPaperPositions(): List<WorkspacePaperPosition> =
        jdbcTemplate.query(
            "select id, market, ticker, name, average_price, current_price, quantity, return_rate from signal_desk_paper_positions order by name",
            paperPositionRowMapper,
        )

    override fun savePaperPosition(position: WorkspacePaperPosition): WorkspacePaperPosition {
        val nextPosition = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_paper_positions (id, market, ticker, name, average_price, current_price, quantity, return_rate)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                average_price = excluded.average_price,
                current_price = excluded.current_price,
                quantity = excluded.quantity,
                return_rate = excluded.return_rate
            """.trimIndent(),
            nextPosition.id, nextPosition.market, nextPosition.ticker, nextPosition.name, nextPosition.averagePrice, nextPosition.currentPrice, nextPosition.quantity, nextPosition.returnRate,
        )
        return nextPosition
    }

    override fun deletePaperPosition(id: String) {
        jdbcTemplate.update("delete from signal_desk_paper_positions where id = ?", id)
    }

    override fun loadPaperTrades(): List<WorkspacePaperTrade> =
        jdbcTemplate.query(
            "select id, trade_date, side, market, ticker, name, price, quantity from signal_desk_paper_trades order by trade_date desc, name",
            paperTradeRowMapper,
        )

    override fun savePaperTrade(trade: WorkspacePaperTrade): WorkspacePaperTrade {
        val nextTrade = trade.copy(id = trade.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_paper_trades (id, trade_date, side, market, ticker, name, price, quantity)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                trade_date = excluded.trade_date,
                side = excluded.side,
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                price = excluded.price,
                quantity = excluded.quantity
            """.trimIndent(),
            nextTrade.id, nextTrade.tradeDate, nextTrade.side, nextTrade.market, nextTrade.ticker, nextTrade.name, nextTrade.price, nextTrade.quantity,
        )
        return nextTrade
    }

    override fun deletePaperTrade(id: String) {
        jdbcTemplate.update("delete from signal_desk_paper_trades where id = ?", id)
    }

    override fun loadAiPicks(): List<WorkspaceAiPick> =
        jdbcTemplate.query(
            "select id, market, ticker, name, basis, confidence, note, expected_return_rate from signal_desk_ai_picks order by confidence desc, name",
            aiPickRowMapper,
        )

    override fun saveAiPick(pick: WorkspaceAiPick): WorkspaceAiPick {
        val nextPick = pick.copy(id = pick.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_ai_picks (id, market, ticker, name, basis, confidence, note, expected_return_rate)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                market = excluded.market,
                ticker = excluded.ticker,
                name = excluded.name,
                basis = excluded.basis,
                confidence = excluded.confidence,
                note = excluded.note,
                expected_return_rate = excluded.expected_return_rate
            """.trimIndent(),
            nextPick.id, nextPick.market, nextPick.ticker, nextPick.name, nextPick.basis, nextPick.confidence, nextPick.note, nextPick.expectedReturnRate,
        )
        return nextPick
    }

    override fun deleteAiPick(id: String) {
        jdbcTemplate.update("delete from signal_desk_ai_picks where id = ?", id)
    }

    override fun loadAiTrackRecords(): List<WorkspaceAiTrackRecord> =
        jdbcTemplate.query(
            "select id, recommended_date, market, ticker, name, entry_price, latest_price, realized_return_rate, success from signal_desk_ai_track_records order by recommended_date desc, name",
            aiTrackRecordRowMapper,
        )

    override fun saveAiTrackRecord(record: WorkspaceAiTrackRecord): WorkspaceAiTrackRecord {
        val nextRecord = record.copy(id = record.id.ifBlank { UUID.randomUUID().toString() })
        jdbcTemplate.update(
            """
            insert into signal_desk_ai_track_records (id, recommended_date, market, ticker, name, entry_price, latest_price, realized_return_rate, success)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            nextRecord.id, nextRecord.recommendedDate, nextRecord.market, nextRecord.ticker, nextRecord.name, nextRecord.entryPrice, nextRecord.latestPrice, nextRecord.realizedReturnRate, nextRecord.success,
        )
        return nextRecord
    }

    override fun deleteAiTrackRecord(id: String) {
        jdbcTemplate.update("delete from signal_desk_ai_track_records where id = ?", id)
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
        profitAmount = rs.getInt("profit_amount"),
        evaluationAmount = rs.getInt("evaluation_amount"),
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
