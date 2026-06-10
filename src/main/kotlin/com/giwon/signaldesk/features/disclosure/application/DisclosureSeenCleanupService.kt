package com.giwon.signaldesk.features.disclosure.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * DART(KR) / SEC EDGAR(US) dedup 테이블의 오래된 row 정리 + 현황 조회.
 *
 * 푸시·HiddenSignals 는 '최근 7일' 만 본다 — 더 오래된 row 는 dedup 가드 외 역할이 없다.
 * 정리는 seen_at 기준 age-based 만 안전하다. (importance LOW 등을 지우면 다음 스캔에서
 * 재감지 → 재푸시되므로 절대 age 외 기준으로 지우지 않는다.)
 *
 * 스케줄러(매일 03:30)와 수동 정리 엔드포인트가 이 로직을 공유한다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DisclosureSeenCleanupService(
    private val jdbc: JdbcTemplate,
) {
    /** retentionDays 보다 오래된 dedup row 삭제. 삭제 건수 반환. */
    fun cleanup(retentionDays: Int): Deleted {
        val days = retentionDays.coerceAtLeast(1)
        val kr = jdbc.update(
            "delete from signal_desk_disclosure_seen where seen_at < now() - make_interval(days => ?)", days,
        )
        val us = jdbc.update(
            "delete from signal_desk_us_disclosure_seen where seen_at < now() - make_interval(days => ?)", days,
        )
        return Deleted(kr, us)
    }

    /** 현황: 테이블별 총 건수 + 가장 오래된 seen_at + 보관기간 후보별 '삭제 대상' 건수. */
    fun stats(): CleanupStats = CleanupStats(
        kr = tableStats("signal_desk_disclosure_seen"),
        us = tableStats("signal_desk_us_disclosure_seen"),
    )

    private fun tableStats(table: String): TableStats {
        val total = jdbc.queryForObject("select count(*) from $table", Int::class.java) ?: 0
        val oldest = jdbc.queryForObject(
            "select to_char(min(seen_at), 'YYYY-MM-DD') from $table", String::class.java,
        )
        // 보관기간 후보별로 '지워질' 건수 미리보기 (7/14/30/60일).
        val olderThan = listOf(7, 14, 30, 60).associateWith { d ->
            jdbc.queryForObject(
                "select count(*) from $table where seen_at < now() - make_interval(days => ?)", Int::class.java, d,
            ) ?: 0
        }
        return TableStats(total = total, oldest = oldest, olderThan = olderThan)
    }

    data class Deleted(val kr: Int, val us: Int)
    data class CleanupStats(val kr: TableStats, val us: TableStats)
    data class TableStats(val total: Int, val oldest: String?, val olderThan: Map<Int, Int>)
}
