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
            )
        )
    }

    fun savePaperPosition(
        userId: UUID? = null,
        id: String, market: String, ticker: String, name: String,
        averagePrice: Int, currentPrice: Int, quantity: Int,
    ): WorkspacePaperPosition {
        val returnRate = if (averagePrice == 0) 0.0
        else ((currentPrice - averagePrice).toDouble() / averagePrice) * 100
        return repository.savePaperPosition(
            userId,
            WorkspacePaperPosition(
                id = id, market = market, ticker = ticker, name = name,
                averagePrice = averagePrice, currentPrice = currentPrice,
                quantity = quantity, returnRate = returnRate,
            )
        )
    }

    fun saveAiTrackRecord(
        userId: UUID? = null,
        id: String, recommendedDate: String, market: String, ticker: String, name: String,
        entryPrice: Int, latestPrice: Int,
    ): WorkspaceAiTrackRecord {
        val realizedReturnRate = if (entryPrice == 0) 0.0
        else ((latestPrice - entryPrice).toDouble() / entryPrice) * 100
        return repository.saveAiTrackRecord(
            userId,
            WorkspaceAiTrackRecord(
                id = id, recommendedDate = recommendedDate, market = market,
                ticker = ticker, name = name, entryPrice = entryPrice,
                latestPrice = latestPrice, realizedReturnRate = realizedReturnRate,
                success = realizedReturnRate >= 0,
            )
        )
    }
}
