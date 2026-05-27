package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.repository.LeagueRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * League 자동 상태 전환:
 *  - OPEN + startedAt 도달 → RUNNING (auto-start)
 *  - RUNNING + endsAt 도달 → 정산 + FINISHED (auto-finish)
 *
 * 매 분 폴링 — 정확도 ±60초 (게임이라 OK).
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class LeagueSchedulerService(
    private val leagues: LeagueRepository,
    private val leaderboard: LeaderboardService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 매 분 0초. */
    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    fun tick() {
        val now = Instant.now()
        runCatching { startReady(now) }.onFailure { log.error("auto-start failed", it) }
        runCatching { finishExpired(now) }.onFailure { log.error("auto-finish failed", it) }
    }

    private fun startReady(now: Instant) {
        val ready = leagues.findOpenReadyToStart(now)
        if (ready.isEmpty()) return
        ready.forEach { l ->
            leagues.updateStatus(l.id, LeagueStatus.RUNNING)
            log.info("league auto-started — id={} name={}", l.id, l.name)
        }
    }

    private fun finishExpired(now: Instant) {
        val expired = leagues.findRunningReadyToFinish(now)
        if (expired.isEmpty()) return
        expired.forEach { l ->
            runCatching { leaderboard.finalize(l.id) }
                .onFailure { log.error("finalize failed — id={}", l.id, it) }
        }
    }
}
