package com.giwon.signaldesk.features.market.application

import java.math.BigDecimal

/** JSON uses numbers for existing clients; monetary arithmetic and persistence retain decimals. */
object PortfolioValuation {
    fun amount(price: Double, quantity: Double): Double =
        BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(quantity)).toDouble()

    fun validate(market: String, price: Double, quantity: Double) {
        require(market in setOf("KR", "US")) { "지원하지 않는 시장입니다." }
        require(listOf(price, quantity).all { it.isFinite() && it > 0 && it <= 1_000_000_000.0 }) { "가격과 수량을 확인해 주세요." }
        require(listOf(price, quantity).all { BigDecimal.valueOf(it).stripTrailingZeros().scale() <= 8 }) { "소수점은 8자리까지 입력할 수 있습니다." }
        require(market != "KR" || (price % 1.0 == 0.0 && quantity % 1.0 == 0.0)) { "국내 주식은 원 단위 가격과 정수 수량을 입력해 주세요." }
    }

    fun summarize(positions: List<HoldingPosition>): PortfolioSummary {
        val groups = positions.groupBy { if (it.market == "US") "USD" else "KRW" }.map { (currency, rows) ->
            val cost = rows.fold(BigDecimal.ZERO) { a, p -> a + BigDecimal.valueOf(p.buyPrice) * BigDecimal.valueOf(p.quantity) }
            val value = rows.fold(BigDecimal.ZERO) { a, p -> a + BigDecimal.valueOf(p.currentPrice) * BigDecimal.valueOf(p.quantity) }
            val profit = value - cost
            CurrencyPortfolioTotal(currency, cost.toDouble(), value.toDouble(), profit.toDouble(),
                if (cost.signum() == 0) 0.0 else profit.toDouble() / cost.toDouble() * 100)
        }.sortedBy { it.currency }
        val one = groups.singleOrNull()
        return PortfolioSummary(
            totalCost = one?.totalCost ?: if (groups.isEmpty()) 0.0 else null,
            totalValue = one?.totalValue ?: if (groups.isEmpty()) 0.0 else null,
            totalProfit = one?.totalProfit ?: if (groups.isEmpty()) 0.0 else null,
            totalProfitRate = one?.totalProfitRate ?: if (groups.isEmpty()) 0.0 else null,
            positions = positions, currencyTotals = groups, totalCurrency = one?.currency,
        )
    }
}

data class CurrencyPortfolioTotal(val currency: String, val totalCost: Double, val totalValue: Double,
    val totalProfit: Double, val totalProfitRate: Double)
