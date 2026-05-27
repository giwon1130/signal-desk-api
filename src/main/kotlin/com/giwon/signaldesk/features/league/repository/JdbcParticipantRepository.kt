package com.giwon.signaldesk.features.league.repository

import com.giwon.signaldesk.features.league.domain.Participant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcParticipantRepository(
    private val jdbc: JdbcTemplate,
) : ParticipantRepository {

    private val mapper = RowMapper<Participant> { rs: ResultSet, _ ->
        Participant(
            leagueId = UUID.fromString(rs.getString("league_id")),
            userId = UUID.fromString(rs.getString("user_id")),
            joinedAt = rs.getTimestamp("joined_at").toInstant(),
            nickname = rs.getString("nickname"),
            avatarEmoji = rs.getString("avatar_emoji"),
            cashBalance = rs.getLong("cash_balance"),
            finalReturnRate = rs.getBigDecimal("final_return_rate"),
            finalRank = rs.getObject("final_rank") as Int?,
        )
    }

    override fun add(p: Participant): Participant {
        jdbc.update(
            """
            insert into signal_desk_mock_participant
              (league_id, user_id, joined_at, nickname, avatar_emoji, cash_balance)
            values (?::uuid, ?::uuid, ?, ?, ?, ?)
            """.trimIndent(),
            p.leagueId.toString(), p.userId.toString(),
            Timestamp.from(p.joinedAt), p.nickname, p.avatarEmoji, p.cashBalance,
        )
        return p
    }

    override fun findByLeague(leagueId: UUID): List<Participant> =
        jdbc.query(
            """
            select * from signal_desk_mock_participant
            where league_id = ?::uuid
            order by final_rank nulls last, joined_at asc
            """.trimIndent(),
            mapper, leagueId.toString(),
        )

    override fun find(leagueId: UUID, userId: UUID): Participant? =
        jdbc.query(
            "select * from signal_desk_mock_participant where league_id = ?::uuid and user_id = ?::uuid",
            mapper, leagueId.toString(), userId.toString(),
        ).firstOrNull()

    override fun updateCashBalance(leagueId: UUID, userId: UUID, newBalance: Long) {
        jdbc.update(
            """
            update signal_desk_mock_participant
            set cash_balance = ?
            where league_id = ?::uuid and user_id = ?::uuid
            """.trimIndent(),
            newBalance, leagueId.toString(), userId.toString(),
        )
    }

    override fun finalizeRanking(leagueId: UUID, userId: UUID, returnRate: BigDecimal, rank: Int) {
        jdbc.update(
            """
            update signal_desk_mock_participant
            set final_return_rate = ?, final_rank = ?
            where league_id = ?::uuid and user_id = ?::uuid
            """.trimIndent(),
            returnRate, rank, leagueId.toString(), userId.toString(),
        )
    }

    override fun delete(leagueId: UUID, userId: UUID) {
        jdbc.update(
            "delete from signal_desk_mock_participant where league_id = ?::uuid and user_id = ?::uuid",
            leagueId.toString(), userId.toString(),
        )
    }

    override fun count(leagueId: UUID): Int =
        jdbc.queryForObject(
            "select count(*) from signal_desk_mock_participant where league_id = ?::uuid",
            Int::class.java, leagueId.toString(),
        ) ?: 0
}
