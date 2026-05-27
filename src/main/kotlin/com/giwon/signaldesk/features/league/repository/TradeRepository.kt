package com.giwon.signaldesk.features.league.repository

import com.giwon.signaldesk.features.league.domain.Trade
import java.util.UUID

/**
 * Trade 는 immutable — 본 인터페이스에 update/delete 없음.
 * 정정은 새 INSERT (역방향 trade) 로만.
 */
interface TradeRepository {
    fun insert(trade: Trade): Trade
    fun findByLeague(leagueId: UUID, limit: Int = 100): List<Trade>
    fun findByUserInLeague(leagueId: UUID, userId: UUID): List<Trade>
}
