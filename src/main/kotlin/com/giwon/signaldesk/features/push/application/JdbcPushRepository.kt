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
        direction: AlertDirection, date: LocalDate, changeRate: Double, reason: String?,
    ) {
        // 같은 종목/방향/날짜 재알림(더 강한 변동)이면 행을 갱신 — 최신 변동률·사유로 덮고
        // read_at=null 로 되돌려 '안 읽음'으로 다시 뜨게(새 푸시가 갔으므로). sent_at 도 갱신해 최상단으로.
        jdbcTemplate.update(
            """
            insert into signal_desk_push_alert_log (id, user_id, market, ticker, name, direction, alert_date, change_rate, reason)
            values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, ticker, direction, alert_date) do update set
                change_rate = excluded.change_rate,
                reason      = coalesce(excluded.reason, signal_desk_push_alert_log.reason),
                name        = excluded.name,
                sent_at     = now(),
                read_at     = null
            """.trimIndent(),
            UUID.randomUUID().toString(), userId.toString(), market, ticker, name, direction.name,
            java.sql.Date.valueOf(date), changeRate, reason,
        )
    }

    override fun loadRecentAlertedRates(sinceDate: LocalDate): Map<AlertRateKey, Double> {
        val rows = jdbcTemplate.query(
            """
            select user_id, ticker, direction, max(abs(change_rate)) as max_rate
            from signal_desk_push_alert_log
            where alert_date >= ? and direction in ('UP','DOWN')
            group by user_id, ticker, direction
            """.trimIndent(),
            { rs, _ ->
                AlertRateKey(
                    userId = UUID.fromString(rs.getString("user_id")),
                    ticker = rs.getString("ticker"),
                    direction = AlertDirection.valueOf(rs.getString("direction")),
                ) to rs.getDouble("max_rate")
            },
            java.sql.Date.valueOf(sinceDate),
        )
        return rows.toMap()
    }

    override fun clearPriceAlert(userId: UUID, ticker: String, clearAbove: Boolean) {
        val column = if (clearAbove) "alert_above" else "alert_below"
        jdbcTemplate.update(
            "update signal_desk_watchlist set $column = null where user_id = ?::uuid and ticker = ?",
            userId.toString(), ticker,
        )
    }

    override fun listAlertHistory(userId: UUID, limit: Int): List<AlertHistoryItem> =
        jdbcTemplate.query(
            """
            select id, market, ticker, name, direction, change_rate, reason, alert_date, sent_at, read_at
            from signal_desk_push_alert_log
            where user_id = ?::uuid
            order by sent_at desc
            limit ?
            """.trimIndent(),
            { rs, _ ->
                AlertHistoryItem(
                    id = rs.getString("id"),
                    market = rs.getString("market") ?: "",
                    ticker = rs.getString("ticker"),
                    name = rs.getString("name") ?: rs.getString("ticker"),
                    direction = AlertDirection.valueOf(rs.getString("direction")),
                    changeRate = rs.getDouble("change_rate"),
                    reason = rs.getString("reason"),
                    alertDate = rs.getDate("alert_date").toString(),
                    sentAt = rs.getTimestamp("sent_at").toInstant().toString(),
                    readAt = rs.getTimestamp("read_at")?.toInstant()?.toString(),
                )
            },
            userId.toString(), limit,
        )

    override fun markAllAlertsRead(userId: UUID): Int =
        jdbcTemplate.update(
            "update signal_desk_push_alert_log set read_at = now() where user_id = ?::uuid and read_at is null",
            userId.toString(),
        )

    override fun deleteAlert(userId: UUID, id: UUID): Boolean =
        jdbcTemplate.update(
            "delete from signal_desk_push_alert_log where id = ?::uuid and user_id = ?::uuid",
            id.toString(), userId.toString(),
        ) > 0

    override fun clearAlerts(userId: UUID): Int =
        jdbcTemplate.update(
            "delete from signal_desk_push_alert_log where user_id = ?::uuid",
            userId.toString(),
        )

    override fun alertStats(days: Int): AlertStats {
        val sinceDays = days.coerceIn(1, 90)
        val sinceDate = LocalDate.now().minusDays(sinceDays.toLong() - 1)

        val totalCount = jdbcTemplate.queryForObject(
            "select count(*) from signal_desk_push_alert_log where alert_date >= ?",
            Int::class.java, sinceDate,
        ) ?: 0
        val uniqueUsers = jdbcTemplate.queryForObject(
            "select count(distinct user_id) from signal_desk_push_alert_log where alert_date >= ?",
            Int::class.java, sinceDate,
        ) ?: 0
        val uniqueTickers = jdbcTemplate.queryForObject(
            "select count(distinct (market || ':' || ticker)) from signal_desk_push_alert_log where alert_date >= ?",
            Int::class.java, sinceDate,
        ) ?: 0

        val byDate = jdbcTemplate.query(
            """
            select alert_date::text as d, count(*) as c
            from signal_desk_push_alert_log
            where alert_date >= ?
            group by alert_date
            order by alert_date desc
            """.trimIndent(),
            { rs, _ -> DateCount(rs.getString("d"), rs.getInt("c")) },
            sinceDate,
        )
        val byMarket = jdbcTemplate.query(
            """
            select market as k, count(*) as c
            from signal_desk_push_alert_log
            where alert_date >= ?
            group by market
            order by c desc
            """.trimIndent(),
            { rs, _ -> KeyCount(rs.getString("k") ?: "?", rs.getInt("c")) },
            sinceDate,
        )
        val byDirection = jdbcTemplate.query(
            """
            select direction as k, count(*) as c
            from signal_desk_push_alert_log
            where alert_date >= ?
            group by direction
            order by c desc
            """.trimIndent(),
            { rs, _ -> KeyCount(rs.getString("k") ?: "?", rs.getInt("c")) },
            sinceDate,
        )
        val topTickers = jdbcTemplate.query(
            """
            select market, ticker, coalesce(max(name), ticker) as name, count(*) as c
            from signal_desk_push_alert_log
            where alert_date >= ?
            group by market, ticker
            order by c desc
            limit 10
            """.trimIndent(),
            { rs, _ ->
                TickerCount(
                    market = rs.getString("market") ?: "?",
                    ticker = rs.getString("ticker"),
                    name = rs.getString("name"),
                    count = rs.getInt("c"),
                )
            },
            sinceDate,
        )

        return AlertStats(
            totalCount = totalCount,
            uniqueUsers = uniqueUsers,
            uniqueTickers = uniqueTickers,
            byDate = byDate,
            byMarket = byMarket,
            byDirection = byDirection,
            topTickers = topTickers,
        )
    }

    private val deviceRowMapper = { rs: ResultSet, _: Int ->
        PushDevice(
            id = UUID.fromString(rs.getString("id")),
            userId = UUID.fromString(rs.getString("user_id")),
            platform = rs.getString("platform"),
            expoToken = rs.getString("expo_token"),
        )
    }
}
