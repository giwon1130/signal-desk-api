package com.giwon.signaldesk.features.push.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcPushRepository(
    private val jdbcTemplate: JdbcTemplate,
) : PushRepository {

    override fun upsertDevice(userId: UUID, platform: String, expoToken: String): PushDevice {
        val existing = jdbcTemplate.query(
            "select id, user_id, platform, expo_token from signal_desk_push_devices where expo_token = ?",
            deviceRowMapper, expoToken,
        ).firstOrNull()
        if (existing != null) {
            jdbcTemplate.update(
                "update signal_desk_push_devices set user_id = ?::uuid, platform = ?, last_seen_at = now() where expo_token = ?",
                userId.toString(), platform, expoToken,
            )
            return existing.copy(userId = userId, platform = platform)
        }
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into signal_desk_push_devices (id, user_id, platform, expo_token) values (?::uuid, ?::uuid, ?, ?)",
            id.toString(), userId.toString(), platform, expoToken,
        )
        return PushDevice(id, userId, platform, expoToken)
    }

    override fun deleteDevice(userId: UUID, expoToken: String) {
        jdbcTemplate.update(
            "delete from signal_desk_push_devices where user_id = ?::uuid and expo_token = ?",
            userId.toString(), expoToken,
        )
    }

    override fun listDevices(userId: UUID): List<PushDevice> =
        jdbcTemplate.query(
            "select id, user_id, platform, expo_token from signal_desk_push_devices where user_id = ?::uuid",
            deviceRowMapper, userId.toString(),
        )

    override fun listAllDevicesGroupedByUser(): Map<UUID, List<PushDevice>> =
        jdbcTemplate.query(
            "select id, user_id, platform, expo_token from signal_desk_push_devices",
            deviceRowMapper,
        ).groupBy { it.userId }

    override fun loadRecentAlertLog(date: LocalDate): Set<AlertLogEntry> =
        jdbcTemplate.query(
            "select user_id, ticker, direction, alert_date from signal_desk_push_alert_log where alert_date = ?",
            { rs, _ ->
                AlertLogEntry(
                    userId = UUID.fromString(rs.getString("user_id")),
                    ticker = rs.getString("ticker"),
                    direction = AlertDirection.valueOf(rs.getString("direction")),
                    date = rs.getDate("alert_date").toLocalDate(),
                )
            },
            java.sql.Date.valueOf(date),
        ).toSet()

    override fun recordAlert(
        userId: UUID, market: String, ticker: String, name: String,
        direction: AlertDirection, date: LocalDate, changeRate: Double,
    ) {
        jdbcTemplate.update(
            """
            insert into signal_desk_push_alert_log (id, user_id, market, ticker, name, direction, alert_date, change_rate)
            values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, ticker, direction, alert_date) do nothing
            """.trimIndent(),
            UUID.randomUUID().toString(), userId.toString(), market, ticker, name, direction.name,
            java.sql.Date.valueOf(date), changeRate,
        )
    }

    override fun listAlertHistory(userId: UUID, limit: Int): List<AlertHistoryItem> =
        jdbcTemplate.query(
            """
            select market, ticker, name, direction, change_rate, alert_date, sent_at
            from signal_desk_push_alert_log
            where user_id = ?::uuid
            order by sent_at desc
            limit ?
            """.trimIndent(),
            { rs, _ ->
                AlertHistoryItem(
                    market = rs.getString("market") ?: "",
                    ticker = rs.getString("ticker"),
                    name = rs.getString("name") ?: rs.getString("ticker"),
                    direction = AlertDirection.valueOf(rs.getString("direction")),
                    changeRate = rs.getDouble("change_rate"),
                    alertDate = rs.getDate("alert_date").toString(),
                    sentAt = rs.getTimestamp("sent_at").toInstant().toString(),
                )
            },
            userId.toString(), limit,
        )

    private val deviceRowMapper = { rs: ResultSet, _: Int ->
        PushDevice(
            id = UUID.fromString(rs.getString("id")),
            userId = UUID.fromString(rs.getString("user_id")),
            platform = rs.getString("platform"),
            expoToken = rs.getString("expo_token"),
        )
    }
}
