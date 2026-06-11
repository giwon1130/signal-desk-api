package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.MarketScope
import com.giwon.signaldesk.features.league.domain.Position
import com.giwon.signaldesk.features.league.domain.Trade
import com.giwon.signaldesk.features.league.domain.TradeSide
import com.giwon.signaldesk.features.league.repository.LeagueRepository
import com.giwon.signaldesk.features.league.repository.TradeRepository
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
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
    private val leagues: LeagueRepository,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
    private val fred: FredIndexClient,
) {

    /** 현재가(league 통화 환산)·수익률을 곁들인 포지션 뷰. */
    data class PositionView(
        val position: Position,
        val currentPrice: BigDecimal?,   // league 통화, 1주당. 시세 없으면 null
        val returnPct: Double?,          // (현재가 - 평단) / 평단 × 100. 시세 없으면 null
    )

    /** league 안 user 의 현재 보유 종목 목록 (수량 > 0 인 것만). */
    fun positionsForUser(leagueId: UUID, userId: UUID): List<Position> {
        val all = trades.findByUserInLeague(leagueId, userId)
        return aggregate(all).filter { it.quantity > 0 }
    }

    /** league 전체 참가자의 보유 종목 — 거래 1회 조회 후 사용자별 합산 (리더보드 N+1 방지). */
    fun positionsByUser(leagueId: UUID): Map<UUID, List<Position>> =
        trades.findAllByLeague(leagueId)
            .groupBy { it.userId }
            .mapValues { (_, ts) -> aggregate(ts).filter { it.quantity > 0 } }

    /** 보유 종목 + 현재가/수익률 (LeaderboardService 평가 로직과 동일한 통화 환산). */
    fun positionViewsForUser(leagueId: UUID, userId: UUID): List<PositionView> {
        val positions = positionsForUser(leagueId, userId)
        if (positions.isEmpty()) return emptyList()
        val league = leagues.findById(leagueId)
            ?: return positions.map { PositionView(it, null, null) }

        val krTickers = positions.filter { it.market == "KR" }.map { it.ticker }.toSet()
        val usTickers = positions.filter { it.market == "US" }.map { it.ticker }.toSet()
        val krPrices = if (krTickers.isNotEmpty()) runCatching { krQuotes.fetchKoreanQuotes(krTickers) }.getOrNull().orEmpty() else emptyMap()
        val usPrices = if (usTickers.isNotEmpty()) runCatching { usQuotes.fetchUsQuotes(usTickers) }.getOrNull().orEmpty() else emptyMap()
        val usdKrw = if (league.marketScope == MarketScope.BOTH)
            runCatching { fred.fetchMacro()?.usdKrw?.currentValue ?: 1.0 }.getOrDefault(1.0) else 1.0

        return positions.map { p ->
            val q = (if (p.market == "KR") krPrices else usPrices)[p.ticker]
            if (q == null || q.exactPrice <= 0) return@map PositionView(p, null, null)
            val originalCcy = if (p.market == "KR") LeagueCurrency.KRW else LeagueCurrency.USD
            val ex = when {
                originalCcy == league.currency -> 1.0
                originalCcy == LeagueCurrency.USD && league.currency == LeagueCurrency.KRW -> usdKrw
                originalCcy == LeagueCurrency.KRW && league.currency == LeagueCurrency.USD -> 1.0 / usdKrw
                else -> 1.0
            }
            val priceLeagueCcy = BigDecimal.valueOf(q.exactPrice).multiply(BigDecimal(ex)).setScale(4, RoundingMode.HALF_UP)
            val avg = p.averageCost
            val ret = if (avg > BigDecimal.ZERO)
                priceLeagueCcy.subtract(avg).divide(avg, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toDouble()
            else null
            PositionView(p, priceLeagueCcy, ret)
        }
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
