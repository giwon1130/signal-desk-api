package com.giwon.signaldesk.features.disclosure.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * DART(KR) / SEC EDGAR(US) dedup 테이블의 오래된 row 정리.
 *
 * 두 테이블 모두 푸시·HiddenSignals 가 '최근 7일' 만 본다 — 더 오래된 row 는 dedup 가드 외 역할이 없다.
 * 60일 보관(보수적)으로 무한 누적 → Railway DB 비용/속도 영향 방지.
 *
 * 새벽 03:30 KST — 트래픽 적고 다른 scheduler 와 겹치지 않는 시간대.
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DisclosureSeenCleanupScheduler(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    fun runDaily() {
        runCatching {
            val krDeleted = jdbc.update(
                "delete from signal_desk_disclosure_seen where seen_at < now() - interval '$RETENTION_DAYS days'"
            )
            val usDeleted = jdbc.update(
                "delete from signal_desk_us_disclosure_seen where seen_at < now() - interval '$RETENTION_DAYS days'"
            )
            if (krDeleted > 0 || usDeleted > 0) {
                log.info("DisclosureSeen cleanup — kr={}, us={} (retention={}d)", krDeleted, usDeleted, RETENTION_DAYS)
            } else {
                log.debug("DisclosureSeen cleanup — nothing to delete")
            }
        }.onFailure {
            log.error("DisclosureSeen cleanup failed", it)
        }
    }

    companion object {
        private const val RETENTION_DAYS = 60
    }
}
