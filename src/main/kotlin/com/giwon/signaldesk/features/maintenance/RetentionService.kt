package com.giwon.signaldesk.features.maintenance

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * 무한 증가 테이블의 age-based 보존 정리. (DisclosureSeen 정리는 별도 — 이미 있음.)
 *
 * 모두 age 기준만 사용 — 최근 데이터만 사용자/로직에 노출되므로 안전.
 *  - push_alert_log: 순수 발송 로그(알림함은 최근만 조회). 60일.
 *  - daily_portfolio_snapshot: 사용자×일 누적(차트 히스토리). 400일(1년+α).
 *  - report_call_seen / media_summaries(FLOW_READING 원장): 중복방지용. 오래된 리포트/영상은
 *    소스(최근 RSS/컨센서스)에 더는 안 떠 재발행 위험 없음. 30/90일.
 *
 * market_snapshot(1행/일)·ai_pick_history(적중률 판정 근거)는 작거나 보존 가치가 있어 제외.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class RetentionService(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runRetention(): Map<String, Int> {
        val deleted = linkedMapOf<String, Int>()
        deleted["push_alert_log"] = delete(
            "delete from signal_desk_push_alert_log where sent_at < now() - make_interval(days => ?)", ALERT_LOG_DAYS,
        )
        deleted["daily_portfolio_snapshot"] = delete(
            "delete from signal_desk_daily_portfolio_snapshot where snapshot_date < (current_date - ?)", PORTFOLIO_SNAPSHOT_DAYS,
        )
        deleted["report_call_seen"] = delete(
            "delete from signal_desk_report_call_seen where created_at < now() - make_interval(days => ?)", DEDUP_LEDGER_DAYS,
        )
        deleted["media_summaries"] = delete(
            "delete from signal_desk_media_summaries where created_at < now() - make_interval(days => ?)", MEDIA_DAYS,
        )
        val total = deleted.values.sum()
        if (total > 0) log.info("retention 정리 — {} (총 {}건)", deleted.filterValues { it > 0 }, total)
        return deleted
    }

    private fun delete(sql: String, days: Int): Int =
        runCatching { jdbc.update(sql, days) }.getOrElse { log.warn("retention delete 실패: {}", it.message); 0 }

    companion object {
        const val ALERT_LOG_DAYS = 60
        const val PORTFOLIO_SNAPSHOT_DAYS = 400
        const val DEDUP_LEDGER_DAYS = 30
        const val MEDIA_DAYS = 90
    }
}
