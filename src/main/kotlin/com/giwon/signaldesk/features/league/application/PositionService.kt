package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.Position
import com.giwon.signaldesk.features.league.domain.Trade
import com.giwon.signaldesk.features.league.domain.TradeSide
import com.giwon.signaldesk.features.league.repository.TradeRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Position — 사용자의 league 안 보유 종목. DB 저장 X, trade 합산 derived.
 *
 * 이동평균법 (FIFO 아님):
 *  - averageCost = (∑ BUY notional) / (∑ BUY qty)
 *  - 매도해도 averageCost 그대로, 보유 수량만 줄어듦
 *  - 실현손익은 SELL 시점에 (SELL price - averageCost) × qty 누적
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class PositionService(
    private val trades: TradeRepository,
) {

    /** league 안 user 의 현재 보유 종목 목록 (수량 > 0 인 것만). */
    fun positionsForUser(leagueId: UUID, userId: UUID): List<Position> {
        val all = trades.findByUserInLeague(leagueId, userId)
        return aggregate(all).filter { it.quantity > 0 }
    }

    /**
     * trade 집합을 종목별로 합산. 종목 key = market+ticker.
     */
    fun aggregate(trades: List<Trade>): List<Position> {
        val groups = trades.groupBy { "${it.market}:${it.ticker}" }
        return groups.map { (_, list) ->
            val first = list.first()
            var buyQty = 0L
            var buyNotional = 0L
            var sellQty = 0L
            var sellNotional = 0L
            var realizedPnl = 0L
            // trade 시간 순 정렬 — 매도 시점 averageCost 계산 위해.
            val sorted = list.sortedBy { it.executedAt }
            for (t in sorted) {
                when (t.side) {
                    TradeSide.BUY -> {
                        buyQty += t.quantity
                        buyNotional += t.notionalAmount
                    }
                    TradeSide.SELL -> {
                        // 이 시점의 averageCost = 지금까지의 buyNotional / buyQty
                        val avgAtSell = if (buyQty > 0) buyNotional.toDouble() / buyQty else 0.0
                        val costForSellQty = (avgAtSell * t.quantity).toLong()
                        realizedPnl += t.notionalAmount - costForSellQty - t.feeAmount
                        sellQty += t.quantity
                        sellNotional += t.notionalAmount
                    }
                }
            }
            val netQty = (buyQty - sellQty).toInt()
            val avgCost = if (buyQty > 0)
                BigDecimal(buyNotional).divide(BigDecimal(buyQty), 4, RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            Position(
                market = first.market,
                ticker = first.ticker,
                name = first.name,
                quantity = netQty,
                averageCost = avgCost,
                realizedPnl = realizedPnl,
                totalBuyNotional = buyNotional,
                totalSellNotional = sellNotional,
            )
        }
    }
}
