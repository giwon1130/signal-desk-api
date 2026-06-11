package com.giwon.signaldesk.features.backtest.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 시즈널리티 알고리즘 포트폴리오 — 저장한 '월별 시즌 규칙' CRUD.
 * 저장 시점 통계(평균·승률·표본)를 스냅샷으로 같이 저장해 근거를 남긴다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class SeasonalityRuleService(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val mapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        SeasonalityRule(
            id = rs.getString("id"),
            market = rs.getString("market"),
            ticker = rs.getString("ticker"),
            name = rs.getString("name") ?: rs.getString("ticker"),
            kind = rs.getString("kind"),
            month = rs.getInt("month"),
            meanPct = rs.getObject("mean_pct") as Double?,
            winRatePct = rs.getObject("win_rate_pct") as Double?,
            sampleYears = rs.getObject("sample_years") as Int?,
            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
        )
    }

    fun save(
        userId: UUID, market: String, ticker: String, name: String, kind: String, month: Int,
        meanPct: Double?, winRatePct: Double?, sampleYears: Int?,
    ): SeasonalityRule? {
        if (kind != "BUY_MONTH" && kind != "AVOID_MONTH") return null
        if (month !in 1..12) return null
        jdbcTemplate.update(
            """
            insert into signal_desk_seasonality_rules
                (id, user_id, market, ticker, name, kind, month, mean_pct, win_rate_pct, sample_years, enabled)
            values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, true)
            on conflict (user_id, market, ticker, kind, month) do update set
                name = excluded.name, mean_pct = excluded.mean_pct,
                win_rate_pct = excluded.win_rate_pct, sample_years = excluded.sample_years, enabled = true
            """.trimIndent(),
            UUID.randomUUID().toString(), userId.toString(), market.uppercase(), ticker, name, kind, month,
            meanPct, winRatePct, sampleYears,
        )
        return jdbcTemplate.query(
            "select * from signal_desk_seasonality_rules where user_id = ?::uuid and market = ? and ticker = ? and kind = ? and month = ?",
            mapper, userId.toString(), market.uppercase(), ticker, kind, month,
        ).firstOrNull()
    }

    fun list(userId: UUID): List<SeasonalityRule> =
        jdbcTemplate.query(
            "select * from signal_desk_seasonality_rules where user_id = ?::uuid order by created_at desc",
            mapper, userId.toString(),
        )

    fun delete(userId: UUID, id: UUID): Boolean =
        jdbcTemplate.update(
            "delete from signal_desk_seasonality_rules where id = ?::uuid and user_id = ?::uuid",
            id.toString(), userId.toString(),
        ) > 0

    /** 스케줄러용 — 활성 규칙 전체(소량 테이블). 트리거 판정은 스케줄러가 날짜 계산으로. */
    fun findAllEnabled(): List<DueRule> =
        jdbcTemplate.query(
            "select id, user_id, market, ticker, name, kind, month, mean_pct, win_rate_pct, sample_years, last_notified_on from signal_desk_seasonality_rules where enabled",
            { rs, _ ->
                DueRule(
                    id = UUID.fromString(rs.getString("id")),
                    userId = UUID.fromString(rs.getString("user_id")),
                    market = rs.getString("market"), ticker = rs.getString("ticker"),
                    name = rs.getString("name") ?: rs.getString("ticker"),
                    kind = rs.getString("kind"), month = rs.getInt("month"),
                    meanPct = rs.getObject("mean_pct") as Double?,
                    winRatePct = rs.getObject("win_rate_pct") as Double?,
                    sampleYears = rs.getObject("sample_years") as Int?,
                    lastNotifiedOn = rs.getDate("last_notified_on")?.toLocalDate(),
                )
            },
        )

    /** 발송 기록 — 같은 트리거 윈도우 안에서 중복 발송 방지. */
    fun markNotified(ruleId: UUID, on: java.time.LocalDate) {
        jdbcTemplate.update(
            "update signal_desk_seasonality_rules set last_notified_on = ? where id = ?::uuid",
            java.sql.Date.valueOf(on), ruleId.toString(),
        )
    }
}

data class SeasonalityRule(
    val id: String,
    val market: String,
    val ticker: String,
    val name: String,
    val kind: String,
    val month: Int,
    val meanPct: Double?,
    val winRatePct: Double?,
    val sampleYears: Int?,
    val createdAt: String,
)

/** 스케줄러 내부용 — 발송 대상 규칙(userId 포함). */
data class DueRule(
    val id: UUID,
    val userId: UUID,
    val market: String,
    val ticker: String,
    val name: String,
    val kind: String,
    val month: Int,
    val meanPct: Double?,
    val winRatePct: Double?,
    val sampleYears: Int?,
    val lastNotifiedOn: java.time.LocalDate?,
)
