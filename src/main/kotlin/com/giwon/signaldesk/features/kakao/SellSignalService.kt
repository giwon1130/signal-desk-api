package com.giwon.signaldesk.features.kakao

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiPick
import org.springframework.stereotype.Service

@Service
class SellSignalService(
    private val repository: SignalDeskWorkspaceRepository,
    private val naverClient: NaverFinanceQuoteClient,
) {

    fun evaluate(): List<SellSignal> {
        val positions = repository.loadPortfolioPositions().filter { it.market == "KR" }
        if (positions.isEmpty()) return emptyList()

        val aiPicks = repository.loadAiPicks().associateBy { it.ticker }
        val quotes = naverClient.fetchKoreanQuotes(positions.map { it.ticker })

        return positions.mapNotNull { pos ->
            val quote = quotes[pos.ticker]
            val currentPrice = quote?.currentPrice ?: pos.currentPrice
            val profitRate = if (pos.buyPrice == 0) 0.0
            else ((currentPrice - pos.buyPrice).toDouble() / pos.buyPrice) * 100

            val (level, reason) = classifySellSignal(pos.ticker, profitRate, aiPicks[pos.ticker])
            SellSignal(
                ticker = pos.ticker,
                name = pos.name,
                buyPrice = pos.buyPrice,
                currentPrice = currentPrice,
                profitRate = profitRate,
                level = level,
                reason = reason,
            )
        }.sortedBy { it.level.ordinal }
    }

    private fun classifySellSignal(
        @Suppress("UNUSED_PARAMETER") ticker: String,
        profitRate: Double,
        aiPick: WorkspaceAiPick?,
    ): Pair<SellLevel, String> {
        val reasons = mutableListOf<String>()

        // 손절 판단
        if (profitRate <= -8.0) {
            reasons += "손실 ${formatRate(profitRate)} — 손절 기준 초과"
            return SellLevel.STRONG_SELL to reasons.joinToString(" / ")
        }
        if (profitRate <= -5.0) {
            reasons += "손실 ${formatRate(profitRate)} — 손절 검토 필요"
        }

        // 익절 판단
        if (profitRate >= 10.0) {
            reasons += "수익 ${formatRate(profitRate)} — 목표 수익 달성"
            return SellLevel.TAKE_PROFIT to reasons.joinToString(" / ")
        }
        if (profitRate >= 5.0) {
            reasons += "수익 ${formatRate(profitRate)} — 익절 검토 구간"
        }

        // AI 신뢰도 하락
        if (aiPick != null && aiPick.confidence < 50) {
            reasons += "AI 신뢰도 ${aiPick.confidence} — 낮은 확신"
        }

        return when {
            reasons.isEmpty() -> SellLevel.HOLD to "이상 없음 (${formatRate(profitRate)})"
            profitRate < 0 -> SellLevel.CAUTION to reasons.joinToString(" / ")
            else -> SellLevel.TAKE_PROFIT to reasons.joinToString(" / ")
        }
    }

    private fun formatRate(rate: Double): String =
        if (rate >= 0) "+${"%.1f".format(rate)}%" else "${"%.1f".format(rate)}%"
}
