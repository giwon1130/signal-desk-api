package com.giwon.signaldesk.features.push.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

data class AlertPreferences(
    val krEnabled: Boolean,
    val usEnabled: Boolean,
    val premarketEnabled: Boolean,
)

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AlertPreferenceService(private val jdbc: JdbcTemplate) {

    fun get(userId: UUID): AlertPreferences {
        val row = jdbc.query(
            "select kr_enabled, us_enabled, premarket_enabled from signal_desk_alert_preferences where user_id = ?::uuid",
            { rs, _ ->
                AlertPreferences(
                    krEnabled = rs.getBoolean("kr_enabled"),
                    usEnabled = rs.getBoolean("us_enabled"),
                    premarketEnabled = rs.getBoolean("premarket_enabled"),
                )
            },
            userId.toString(),
        ).firstOrNull()
        return row ?: DEFAULT
    }

    fun update(userId: UUID, request: AlertPreferences): AlertPreferences {
        jdbc.update(
            """
            insert into signal_desk_alert_preferences (user_id, kr_enabled, us_enabled, premarket_enabled, updated_at)
            values (?::uuid, ?, ?, ?, now())
            on conflict (user_id) do update set
                kr_enabled = excluded.kr_enabled,
                us_enabled = excluded.us_enabled,
                premarket_enabled = excluded.premarket_enabled,
                updated_at = now()
            """.trimIndent(),
            userId.toString(), request.krEnabled, request.usEnabled, request.premarketEnabled,
        )
        return request
    }

    fun loadEnabledUsers(market: String, includePremarket: Boolean = false): Set<UUID> {
        val column = when {
            includePremarket -> "premarket_enabled"
            market == "US" -> "us_enabled"
            else -> "kr_enabled"
        }
        // 명시적으로 등록 안 한 사용자는 DEFAULT 적용 — left join + coalesce.
        val defaultValue = when {
            includePremarket -> DEFAULT.premarketEnabled
            market == "US" -> DEFAULT.usEnabled
            else -> DEFAULT.krEnabled
        }
        val sql = """
            select u.id from signal_desk_users u
            left join signal_desk_alert_preferences p on p.user_id = u.id
            where coalesce(p.$column, ?) = true
        """.trimIndent()
        return jdbc.query(sql, { rs, _ -> UUID.fromString(rs.getString("id")) }, defaultValue).toSet()
    }

    companion object {
        val DEFAULT = AlertPreferences(krEnabled = true, usEnabled = false, premarketEnabled = true)
    }
}
