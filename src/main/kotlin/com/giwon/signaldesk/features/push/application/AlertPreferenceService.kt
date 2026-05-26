package com.giwon.signaldesk.features.push.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

data class AlertPreferences(
    val krEnabled: Boolean,
    val usEnabled: Boolean,
    val premarketEnabled: Boolean,
    // 합성 위험도 알림. 구버전 앱이 이 필드 없이 PUT 해도 깨지지 않도록 기본값을 둔다.
    val compositeRiskEnabled: Boolean = true,
    // 투자 시장 선호 — UI 필터링. "KR" | "US" | "BOTH" (기본 BOTH=양쪽 다 노출).
    // 푸시 알림 토글(kr/us_enabled)과 별개로 화면 노출 범위만 결정.
    val marketPreference: String = "BOTH",
)

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AlertPreferenceService(private val jdbc: JdbcTemplate) {

    fun get(userId: UUID): AlertPreferences {
        val row = jdbc.query(
            "select kr_enabled, us_enabled, premarket_enabled, composite_risk_enabled, market_preference from signal_desk_alert_preferences where user_id = ?::uuid",
            { rs, _ ->
                AlertPreferences(
                    krEnabled = rs.getBoolean("kr_enabled"),
                    usEnabled = rs.getBoolean("us_enabled"),
                    premarketEnabled = rs.getBoolean("premarket_enabled"),
                    compositeRiskEnabled = rs.getBoolean("composite_risk_enabled"),
                    marketPreference = rs.getString("market_preference") ?: "BOTH",
                )
            },
            userId.toString(),
        ).firstOrNull()
        return row ?: DEFAULT
    }

    fun update(userId: UUID, request: AlertPreferences): AlertPreferences {
        jdbc.update(
            """
            insert into signal_desk_alert_preferences (user_id, kr_enabled, us_enabled, premarket_enabled, composite_risk_enabled, market_preference, updated_at)
            values (?::uuid, ?, ?, ?, ?, ?, now())
            on conflict (user_id) do update set
                kr_enabled = excluded.kr_enabled,
                us_enabled = excluded.us_enabled,
                premarket_enabled = excluded.premarket_enabled,
                composite_risk_enabled = excluded.composite_risk_enabled,
                market_preference = excluded.market_preference,
                updated_at = now()
            """.trimIndent(),
            userId.toString(), request.krEnabled, request.usEnabled, request.premarketEnabled, request.compositeRiskEnabled,
            request.marketPreference.uppercase().takeIf { it in setOf("KR", "US", "BOTH") } ?: "BOTH",
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

    /** 합성 위험도 알림(composite_risk_enabled) ON 사용자. 미등록 사용자는 DEFAULT 적용. */
    fun loadCompositeRiskEnabledUsers(): Set<UUID> {
        val sql = """
            select u.id from signal_desk_users u
            left join signal_desk_alert_preferences p on p.user_id = u.id
            where coalesce(p.composite_risk_enabled, ?) = true
        """.trimIndent()
        return jdbc.query(sql, { rs, _ -> UUID.fromString(rs.getString("id")) }, DEFAULT.compositeRiskEnabled).toSet()
    }

    companion object {
        val DEFAULT = AlertPreferences(
            krEnabled = true, usEnabled = false, premarketEnabled = true, compositeRiskEnabled = true,
            marketPreference = "BOTH",
        )
    }
}
