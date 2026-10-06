package com.giwon.signaldesk.features.workspace.application

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class WorkspaceService(
    private val repository: SignalDeskWorkspaceRepository,
) {

    fun savePortfolioPosition(
        userId: UUID? = null,
        id: String, market: String, ticker: String, name: String,
        buyPrice: Double, currentPrice: Double, quantity: Double,
        targetPrice: Double? = null, stopLossPrice: Double? = null,
    ): WorkspaceHoldingPosition {
        com.giwon.signaldesk.features.market.application.PortfolioValuation.validate(market, buyPrice, quantity)
        com.giwon.signaldesk.features.market.application.PortfolioValuation.validate(market, currentPrice, quantity)
        listOfNotNull(targetPrice, stopLossPrice).forEach { com.giwon.signaldesk.features.market.application.PortfolioValuation.validate(market, it, quantity) }
        val evaluationAmount = com.giwon.signaldesk.features.market.application.PortfolioValuation.amount(currentPrice, quantity)
        val costAmount = com.giwon.signaldesk.features.market.application.PortfolioValuation.amount(buyPrice, quantity)
        val profitAmount = java.math.BigDecimal.valueOf(evaluationAmount).subtract(java.math.BigDecimal.valueOf(costAmount)).toDouble()
        return repository.savePortfolioPosition(
            userId,
            WorkspaceHoldingPosition(
                id = id, market = market, ticker = ticker, name = name,
                buyPrice = buyPrice, currentPrice = currentPrice, quantity = quantity,
                profitAmount = profitAmount, evaluationAmount = evaluationAmount,
                profitRate = if (costAmount == 0.0) 0.0 else (profitAmount / costAmount) * 100,
                targetPrice = targetPrice, stopLossPrice = stopLossPrice,
            )
        )
    }

}
