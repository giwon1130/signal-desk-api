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
    // US 이브닝 브리프 (06:30 KST = NY 장 마감 직후). 디폴트 false.
    val eveningBriefEnabled: Boolean = false,
    // KR 장중 브리프 (12:30 KST). 디폴트 false.
    val middayBriefEnabled: Boolean = false,
    // KR 마감 브리프 (15:40 KST). 디폴트 false.
    val closeBriefEnabled: Boolean = false,
)

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AlertPreferenceService(private val jdbc: JdbcTemplate) {

    fun get(userId: UUID): AlertPreferences {
        val row = jdbc.query(
            "select kr_enabled, us_enabled, premarket_enabled, composite_risk_enabled, market_preference, evening_brief_enabled, midday_brief_enabled, close_brief_enabled from signal_desk_alert_preferences where user_id = ?::uuid",
            { rs, _ ->
                AlertPreferences(
                    krEnabled = rs.getBoolean("kr_enabled"),
                    usEnabled = rs.getBoolean("us_enabled"),
                    premarketEnabled = rs.getBoolean("premarket_enabled"),
                    compositeRiskEnabled = rs.getBoolean("composite_risk_enabled"),
                    marketPreference = rs.getString("market_preference") ?: "BOTH",
                    eveningBriefEnabled = rs.getBoolean("evening_brief_enabled"),
                    middayBriefEnabled = rs.getBoolean("midday_brief_enabled"),
                    closeBriefEnabled = rs.getBoolean("close_brief_enabled"),
                )
            },
            userId.toString(),
        ).firstOrNull()
        return row ?: DEFAULT
    }

    fun update(userId: UUID, request: AlertPreferences): AlertPreferences {
        jdbc.update(
            """
            insert into signal_desk_alert_preferences (user_id, kr_enabled, us_enabled, premarket_enabled, composite_risk_enabled, market_preference, evening_brief_enabled, midday_brief_enabled, close_brief_enabled, updated_at)
            values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (user_id) do update set
                kr_enabled = excluded.kr_enabled,
                us_enabled = excluded.us_enabled,
                premarket_enabled = excluded.premarket_enabled,
                composite_risk_enabled = excluded.composite_risk_enabled,
                market_preference = excluded.market_preference,
                evening_brief_enabled = excluded.evening_brief_enabled,
                midday_brief_enabled = excluded.midday_brief_enabled,
                close_brief_enabled = excluded.close_brief_enabled,
                updated_at = now()
            """.trimIndent(),
            userId.toString(), request.krEnabled, request.usEnabled, request.premarketEnabled, request.compositeRiskEnabled,
            request.marketPreference.uppercase().takeIf { it in setOf("KR", "US", "BOTH") } ?: "BOTH",
            request.eveningBriefEnabled, request.middayBriefEnabled, request.closeBriefEnabled,
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

    /**
     * US 이브닝 브리프(evening_brief_enabled) ON 사용자. 미등록은 DEFAULT(false) 적용.
     * 추가 가드: marketPreference 가 'KR' 인 사용자는 미장 안 보는 사람이라 자동 제외.
     * (사용자가 토글을 안 끈 채로 KR-only 로 바꿔도 무의미한 푸시 안 가게)
     */
    fun loadEveningBriefEnabledUsers(): Set<UUID> {
        val sql = """
            select u.id from signal_desk_users u
            left join signal_desk_alert_preferences p on p.user_id = u.id
            where coalesce(p.evening_brief_enabled, ?) = true
              and coalesce(p.market_preference, 'BOTH') in ('US', 'BOTH')
        """.trimIndent()
        return jdbc.query(sql, { rs, _ -> UUID.fromString(rs.getString("id")) }, DEFAULT.eveningBriefEnabled).toSet()
    }

    /**
     * KR 장중/마감 브리프 ON 사용자. 미등록은 DEFAULT(false). 미장 전용(market_preference='US') 사용자는
     * KR 브리프 무의미하므로 제외 (KR/BOTH 만).
     * @param column "midday_brief_enabled" | "close_brief_enabled"
     */
    fun loadIntradayBriefEnabledUsers(column: String): Set<UUID> {
        require(column in setOf("midday_brief_enabled", "close_brief_enabled")) { "invalid column: $column" }
        val sql = """
            select u.id from signal_desk_users u
            left join signal_desk_alert_preferences p on p.user_id = u.id
            where coalesce(p.$column, false) = true
              and coalesce(p.market_preference, 'BOTH') in ('KR', 'BOTH')
        """.trimIndent()
        return jdbc.query(sql, { rs, _ -> UUID.fromString(rs.getString("id")) }).toSet()
    }

    companion object {
        val DEFAULT = AlertPreferences(
            krEnabled = true, usEnabled = false, premarketEnabled = true, compositeRiskEnabled = true,
            marketPreference = "BOTH", eveningBriefEnabled = false,
            middayBriefEnabled = false, closeBriefEnabled = false,
        )
    }
}
