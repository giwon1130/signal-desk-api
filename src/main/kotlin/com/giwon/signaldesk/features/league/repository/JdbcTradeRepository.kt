package com.giwon.signaldesk.features.league.repository

import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.Trade
import com.giwon.signaldesk.features.league.domain.TradeSide
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcTradeRepository(
    private val jdbc: JdbcTemplate,
) : TradeRepository {

    private val mapper = RowMapper<Trade> { rs: ResultSet, _ ->
        Trade(
            id = UUID.fromString(rs.getString("id")),
            leagueId = UUID.fromString(rs.getString("league_id")),
            userId = UUID.fromString(rs.getString("user_id")),
            market = rs.getString("market"),
            ticker = rs.getString("ticker"),
            name = rs.getString("name"),
            side = TradeSide.valueOf(rs.getString("side")),
            quantity = rs.getInt("quantity"),
            originalPrice = rs.getBigDecimal("original_price"),
            originalCurrency = LeagueCurrency.valueOf(rs.getString("original_currency")),
            price = rs.getBigDecimal("price"),
            exchangeRate = rs.getBigDecimal("exchange_rate"),
            priceLockedAt = rs.getTimestamp("price_locked_at").toInstant(),
            feeAmount = rs.getLong("fee_amount"),
            notionalAmount = rs.getLong("notional_amount"),
            executedAt = rs.getTimestamp("executed_at").toInstant(),
        )
    }

    override fun insert(trade: Trade): Trade {
        jdbc.update(
            """
            insert into signal_desk_mock_trade
              (id, league_id, user_id, market, ticker, name, side, quantity,
               original_price, original_currency, price, exchange_rate,
               price_locked_at, fee_amount, notional_amount, executed_at)
            values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            trade.id.toString(), trade.leagueId.toString(), trade.userId.toString(),
            trade.market, trade.ticker, trade.name, trade.side.name, trade.quantity,
            trade.originalPrice, trade.originalCurrency.name, trade.price, trade.exchangeRate,
            Timestamp.from(trade.priceLockedAt), trade.feeAmount, trade.notionalAmount,
            Timestamp.from(trade.executedAt),
        )
        return trade
    }

    override fun findByLeague(leagueId: UUID, limit: Int): List<Trade> =
        jdbc.query(
            """
            select * from signal_desk_mock_trade
            where league_id = ?::uuid
            order by executed_at desc
            limit ?
            """.trimIndent(),
            mapper, leagueId.toString(), limit,
        )

    override fun findAllByLeague(leagueId: UUID): List<Trade> =
        jdbc.query(
            "select * from signal_desk_mock_trade where league_id = ?::uuid",
            mapper, leagueId.toString(),
        )

    override fun findByUserInLeague(leagueId: UUID, userId: UUID): List<Trade> =
        jdbc.query(
            """
            select * from signal_desk_mock_trade
            where league_id = ?::uuid and user_id = ?::uuid
            order by executed_at desc
            """.trimIndent(),
            mapper, leagueId.toString(), userId.toString(),
        )
}
