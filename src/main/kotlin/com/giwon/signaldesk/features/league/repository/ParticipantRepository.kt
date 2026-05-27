package com.giwon.signaldesk.features.league.repository

import com.giwon.signaldesk.features.league.domain.Participant
import java.math.BigDecimal
import java.util.UUID

interface ParticipantRepository {
    fun add(p: Participant): Participant
    fun findByLeague(leagueId: UUID): List<Participant>
    fun find(leagueId: UUID, userId: UUID): Participant?
    fun updateCashBalance(leagueId: UUID, userId: UUID, newBalance: Long)
    /** 정산 — final 값 일괄 업데이트. */
    fun finalizeRanking(leagueId: UUID, userId: UUID, returnRate: BigDecimal, rank: Int)
    fun delete(leagueId: UUID, userId: UUID)
    fun count(leagueId: UUID): Int
}
