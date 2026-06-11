package com.giwon.signaldesk.features.push.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
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
    // 거래량 급증 알림 전역 토글 — 종목별 volume_alert 와 별개. 켜면 모든 종목 적용. 디폴트 true.
    val volumeAlertEnabled: Boolean = true,
    // 방해금지: 켜면 야간 시간대 푸시 보류. 디폴트 OFF.
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
)

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AlertPreferenceService(private val jdbc: JdbcTemplate) {

    private val userIdMapper = RowMapper { rs, _ -> UUID.fromString(rs.getString("id")) }

    fun get(userId: UUID): AlertPreferences {
        val row = jdbc.query(
            "select kr_enabled, us_enabled, premarket_enabled, composite_risk_enabled, market_preference, evening_brief_enabled, midday_brief_enabled, close_brief_enabled, volume_alert_enabled, quiet_hours_enabled, quiet_start_hour, quiet_end_hour from signal_desk_alert_preferences where user_id = ?::uuid",
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
                    volumeAlertEnabled = rs.getBoolean("volume_alert_enabled"),
                    quietHoursEnabled = rs.getBoolean("quiet_hours_enabled"),
                    quietStartHour = rs.getInt("quiet_start_hour"),
                    quietEndHour = rs.getInt("quiet_end_hour"),
                )
            },
            userId.toString(),
        ).firstOrNull()
        return row ?: DEFAULT
    }

    fun update(userId: UUID, request: AlertPreferences): AlertPreferences {
        // insert 컬럼 / placeholder / do-update-set 를 UPSERT_COLUMNS 단일 소스에서 생성 (드리프트 방지).
        // 파라미터 순서는 UPSERT_COLUMNS 와 1:1 로 맞춰야 한다 (아래 vararg 순서).
        val cols = UPSERT_COLUMNS.joinToString(", ")
        val placeholders = UPSERT_COLUMNS.joinToString(", ") { "?" }
        val updates = UPSERT_COLUMNS.joinToString(",\n                ") { "$it = excluded.$it" }
        jdbc.update(
            """
            insert into signal_desk_alert_preferences (user_id, $cols, updated_at)
            values (?::uuid, $placeholders, now())
            on conflict (user_id) do update set
                $updates,
                updated_at = now()
            """.trimIndent(),
            userId.toString(), request.krEnabled, request.usEnabled, request.premarketEnabled, request.compositeRiskEnabled,
            request.marketPreference.uppercase().takeIf { it in setOf("KR", "US", "BOTH") } ?: "BOTH",
            request.eveningBriefEnabled, request.middayBriefEnabled, request.closeBriefEnabled,
            request.volumeAlertEnabled, request.quietHoursEnabled,
            request.quietStartHour.coerceIn(0, 23), request.quietEndHour.coerceIn(0, 23),
        )
        return request
    }

    fun loadEnabledUsers(market: String, includePremarket: Boolean = false): Set<UUID> {
        val (column, default) = when {
            includePremarket -> "premarket_enabled" to DEFAULT.premarketEnabled
            market == "US" -> "us_enabled" to DEFAULT.usEnabled
            else -> "kr_enabled" to DEFAULT.krEnabled
        }
        return loadUsersWhere(column, default)
    }

    /** 합성 위험도 알림(composite_risk_enabled) ON 사용자. 미등록 사용자는 DEFAULT 적용. */
    fun loadCompositeRiskEnabledUsers(): Set<UUID> =
        loadUsersWhere("composite_risk_enabled", DEFAULT.compositeRiskEnabled)

    /**
     * US 이브닝 브리프(evening_brief_enabled) ON 사용자. 미등록은 DEFAULT(false) 적용.
     * 추가 가드: marketPreference 가 'KR' 인 사용자는 미장 안 보는 사람이라 자동 제외.
     * (사용자가 토글을 안 끈 채로 KR-only 로 바꿔도 무의미한 푸시 안 가게)
     */
    fun loadEveningBriefEnabledUsers(): Set<UUID> =
        loadUsersWhere("evening_brief_enabled", DEFAULT.eveningBriefEnabled, marketIn = setOf("US", "BOTH"))

    /**
     * KR 장중/마감 브리프 ON 사용자. 미등록은 defaultOn 적용 (마감=기본 ON, 장중=기본 OFF).
     * 미장 전용(market_preference='US') 사용자는 KR 브리프 무의미하므로 제외 (KR/BOTH 만).
     * @param column "midday_brief_enabled" | "close_brief_enabled"
     */
    fun loadIntradayBriefEnabledUsers(column: String, defaultOn: Boolean): Set<UUID> {
        require(column in setOf("midday_brief_enabled", "close_brief_enabled")) { "invalid column: $column" }
        return loadUsersWhere(column, defaultOn, marketIn = setOf("KR", "BOTH"))
    }

    /**
     * users LEFT JOIN alert_preferences 에서 coalesce(p.<column>, default)=true 인 사용자 id.
     * 미등록(prefs row 없음) 사용자는 default 적용. marketIn 주면 market_preference 가드 추가.
     * column/marketIn 은 호출부 상수만 받는다(외부 입력 X) — SQL 인젝션 무관.
     */
    private fun loadUsersWhere(column: String, default: Boolean, marketIn: Set<String>? = null): Set<UUID> {
        val marketClause = marketIn
            ?.let { " and coalesce(p.market_preference, 'BOTH') in (${it.joinToString(",") { m -> "'$m'" }})" }
            ?: ""
        val sql = """
            select u.id from signal_desk_users u
            left join signal_desk_alert_preferences p on p.user_id = u.id
            where coalesce(p.$column, ?) = true$marketClause
        """.trimIndent()
        return jdbc.query(sql, userIdMapper, default).toSet()
    }

    /**
     * 주어진 사용자들 중 거래량 전역 토글(volume_alert_enabled) ON 인 사용자 집합.
     * 미등록 사용자는 DEFAULT(true) 적용.
     */
    fun loadVolumeAlertEnabledUsers(userIds: Set<UUID>): Set<UUID> {
        if (userIds.isEmpty()) return emptySet()
        // u.id 는 uuid — String 바인딩 그대로면 'uuid = character varying' 에러. ::uuid 캐스팅 필수.
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        val sql = """
            select u.id from signal_desk_users u
            left join signal_desk_alert_preferences p on p.user_id = u.id
            where u.id in ($placeholders) and coalesce(p.volume_alert_enabled, ?) = true
        """.trimIndent()
        val params = (userIds.map { it.toString() } + DEFAULT.volumeAlertEnabled).toTypedArray()
        return jdbc.query(sql, userIdMapper, *params).toSet()
    }

    /**
     * 방해금지 ON 사용자의 시간창(quiet_start_hour, quiet_end_hour). OFF/미등록은 맵에 없음(=보류 안 함).
     * 푸시 게이트에서 현재 KST 시각이 이 창 안이면 메시지 보류.
     */
    fun loadQuietHoursFor(userIds: Set<UUID>): Map<UUID, Pair<Int, Int>> {
        if (userIds.isEmpty()) return emptyMap()
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        val sql = """
            select user_id, quiet_start_hour, quiet_end_hour
            from signal_desk_alert_preferences
            where quiet_hours_enabled = true and user_id in ($placeholders)
        """.trimIndent()
        val params = userIds.map { it.toString() }.toTypedArray()
        return jdbc.query(
            sql,
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to (rs.getInt("quiet_start_hour") to rs.getInt("quiet_end_hour")) },
            *params,
        ).toMap()
    }

    companion object {
        /** upsert 의 insert/placeholder/do-update-set 를 생성하는 단일 컬럼 소스 (user_id, updated_at 제외). */
        private val UPSERT_COLUMNS = listOf(
            "kr_enabled", "us_enabled", "premarket_enabled", "composite_risk_enabled", "market_preference",
            "evening_brief_enabled", "midday_brief_enabled", "close_brief_enabled",
            "volume_alert_enabled", "quiet_hours_enabled", "quiet_start_hour", "quiet_end_hour",
        )

        val DEFAULT = AlertPreferences(
            krEnabled = true, usEnabled = false, premarketEnabled = true, compositeRiskEnabled = true,
            marketPreference = "BOTH", eveningBriefEnabled = false,
            middayBriefEnabled = false, closeBriefEnabled = true,
            volumeAlertEnabled = true, quietHoursEnabled = false, quietStartHour = 22, quietEndHour = 7,
        )

        /** 현재 시각(hour, 0-23)이 [start, end) 방해금지 창 안인가. start>end 면 자정 넘김(예: 22~7). */
        fun isWithinQuietHours(nowHour: Int, startHour: Int, endHour: Int): Boolean =
            if (startHour == endHour) false
            else if (startHour < endHour) nowHour in startHour until endHour
            else nowHour >= startHour || nowHour < endHour
    }
}
