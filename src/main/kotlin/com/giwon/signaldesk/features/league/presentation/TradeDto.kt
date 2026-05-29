package com.giwon.signaldesk.features.league.presentation

import com.giwon.signaldesk.features.league.application.PositionService
import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.Position
import com.giwon.signaldesk.features.league.domain.Trade
import com.giwon.signaldesk.features.league.domain.TradeSide
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class PlaceTradeRequest(
    @field:NotBlank val market: String,        // "KR" | "US"
    @field:NotBlank val ticker: String,
    val name: String = "",                     // 클라이언트가 검색 결과의 종목명 전달 (없으면 ticker fallback)
    val side: TradeSide,
    @field:Min(1) val quantity: Int,
)

data class TradeResponse(
    val id: String,
    val leagueId: String,
    val userId: String,
    val market: String,
    val ticker: String,
    val name: String,
    val side: TradeSide,
    val quantity: Int,
    val originalPrice: Double,
    val originalCurrency: LeagueCurrency,
    val price: Double,
    val exchangeRate: Double,
    val feeAmount: Long,
    val notionalAmount: Long,
    val executedAt: String,
) {
    companion object {
        fun from(t: Trade) = TradeResponse(
            id = t.id.toString(), leagueId = t.leagueId.toString(), userId = t.userId.toString(),
            market = t.market, ticker = t.ticker, name = t.name,
            side = t.side, quantity = t.quantity,
            originalPrice = t.originalPrice.toDouble(), originalCurrency = t.originalCurrency,
            price = t.price.toDouble(), exchangeRate = t.exchangeRate.toDouble(),
            feeAmount = t.feeAmount, notionalAmount = t.notionalAmount,
            executedAt = t.executedAt.toString(),
        )
    }
}

data class PositionResponse(
    val market: String,
    val ticker: String,
    val name: String,
    val quantity: Int,
    val averageCost: Double,
    val realizedPnl: Long,
    val currentPrice: Double?,   // league 통화, 1주당 (시세 없으면 null)
    val returnPct: Double?,      // 평단 대비 수익률 % (시세 없으면 null)
) {
    companion object {
        fun from(p: Position) = PositionResponse(
            market = p.market, ticker = p.ticker, name = p.name,
            quantity = p.quantity, averageCost = p.averageCost.toDouble(),
            realizedPnl = p.realizedPnl, currentPrice = null, returnPct = null,
        )

        fun from(v: PositionService.PositionView) = PositionResponse(
            market = v.position.market, ticker = v.position.ticker, name = v.position.name,
            quantity = v.position.quantity, averageCost = v.position.averageCost.toDouble(),
            realizedPnl = v.position.realizedPnl,
            currentPrice = v.currentPrice?.toDouble(), returnPct = v.returnPct,
        )
    }
}
