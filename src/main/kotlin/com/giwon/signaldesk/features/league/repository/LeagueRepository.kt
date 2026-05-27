package com.giwon.signaldesk.features.league.repository

import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import java.time.Instant
import java.util.UUID

/**
 * League CRUD.
 * 구현: [JdbcLeagueRepository] — JDBC mode 만. (signal-desk.store.mode=jdbc)
 */
interface LeagueRepository {
    fun create(league: League): League
    fun findById(id: UUID): League?
    fun findByJoinCode(code: String): League?
    fun findByHost(hostUserId: UUID): List<League>
    fun findByParticipant(userId: UUID): List<League>
    fun updateStatus(id: UUID, status: LeagueStatus)
    /** auto-start cron — startedAt 이미 지났는데 status=OPEN 인 것들. */
    fun findOpenReadyToStart(now: Instant): List<League>
    /** auto-finish cron — endsAt 이미 지났는데 status=RUNNING 인 것들. */
    fun findRunningReadyToFinish(now: Instant): List<League>
}
