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
        buyPrice: Int, currentPrice: Int, quantity: Int,
        targetPrice: Int? = null, stopLossPrice: Int? = null,
    ): WorkspaceHoldingPosition {
        val evaluationAmount = currentPrice.toLong() * quantity
        val costAmount = buyPrice.toLong() * quantity
        val profitAmount = evaluationAmount - costAmount
        return repository.savePortfolioPosition(
            userId,
            WorkspaceHoldingPosition(
                id = id, market = market, ticker = ticker, name = name,
                buyPrice = buyPrice, currentPrice = currentPrice, quantity = quantity,
                profitAmount = profitAmount, evaluationAmount = evaluationAmount,
                profitRate = if (costAmount == 0L) 0.0 else (profitAmount.toDouble() / costAmount) * 100,
                targetPrice = targetPrice, stopLossPrice = stopLossPrice,
            )
        )
    }

}
