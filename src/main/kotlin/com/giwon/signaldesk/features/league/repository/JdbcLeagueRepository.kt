package com.giwon.signaldesk.features.league.repository

import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.domain.LeagueVisibility
import com.giwon.signaldesk.features.league.domain.MarketScope
import com.giwon.signaldesk.features.league.domain.TradingHours
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcLeagueRepository(
    private val jdbc: JdbcTemplate,
) : LeagueRepository {

    private val mapper = RowMapper<League> { rs: ResultSet, _ ->
        League(
            id = UUID.fromString(rs.getString("id")),
            name = rs.getString("name"),
            hostUserId = UUID.fromString(rs.getString("host_user_id")),
            joinCode = rs.getString("join_code"),
            marketScope = MarketScope.valueOf(rs.getString("market_scope")),
            currency = LeagueCurrency.valueOf(rs.getString("currency")),
            startingCapital = rs.getLong("starting_capital"),
            startedAt = rs.getTimestamp("started_at").toInstant(),
            endsAt = rs.getTimestamp("ends_at").toInstant(),
            status = LeagueStatus.valueOf(rs.getString("status")),
            tradingHours = TradingHours.valueOf(rs.getString("trading_hours")),
            fee = rs.getBigDecimal("fee"),
            maxPositionPct = rs.getBigDecimal("max_position_pct"),
            visibility = LeagueVisibility.valueOf(rs.getString("visibility")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    override fun create(league: League): League {
        jdbc.update(
            """
            insert into signal_desk_mock_league
              (id, name, host_user_id, join_code, market_scope, currency, starting_capital,
               started_at, ends_at, status, trading_hours, fee, max_position_pct, visibility, created_at)
            values (?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            league.id.toString(), league.name, league.hostUserId.toString(), league.joinCode,
            league.marketScope.name, league.currency.name, league.startingCapital,
            Timestamp.from(league.startedAt), Timestamp.from(league.endsAt),
            league.status.name, league.tradingHours.name, league.fee, league.maxPositionPct,
            league.visibility.name, Timestamp.from(league.createdAt),
        )
        return league
    }

    override fun findById(id: UUID): League? =
        jdbc.query("select * from signal_desk_mock_league where id = ?::uuid", mapper, id.toString())
            .firstOrNull()

    override fun findByJoinCode(code: String): League? =
        jdbc.query("select * from signal_desk_mock_league where join_code = ?", mapper, code)
            .firstOrNull()

    override fun findByHost(hostUserId: UUID): List<League> =
        jdbc.query(
            "select * from signal_desk_mock_league where host_user_id = ?::uuid order by created_at desc",
            mapper, hostUserId.toString(),
        )

    override fun findByParticipant(userId: UUID): List<League> =
        jdbc.query(
            """
            select l.* from signal_desk_mock_league l
            join signal_desk_mock_participant p on p.league_id = l.id
            where p.user_id = ?::uuid
            order by l.created_at desc
            """.trimIndent(),
            mapper, userId.toString(),
        )

    override fun updateStatus(id: UUID, status: LeagueStatus) {
        jdbc.update(
            "update signal_desk_mock_league set status = ? where id = ?::uuid",
            status.name, id.toString(),
        )
    }

    override fun findOpenReadyToStart(now: Instant): List<League> =
        jdbc.query(
            "select * from signal_desk_mock_league where status = 'OPEN' and started_at <= ?",
            mapper, Timestamp.from(now),
        )

    override fun findRunningReadyToFinish(now: Instant): List<League> =
        jdbc.query(
            "select * from signal_desk_mock_league where status = 'RUNNING' and ends_at <= ?",
            mapper, Timestamp.from(now),
        )
}
