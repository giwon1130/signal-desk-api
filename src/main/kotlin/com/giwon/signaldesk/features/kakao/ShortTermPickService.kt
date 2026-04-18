package com.giwon.signaldesk.features.kakao

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import org.springframework.stereotype.Service

@Service
class ShortTermPickService(
    private val repository: SignalDeskWorkspaceRepository,
    private val naverClient: NaverFinanceQuoteClient,
) {

    fun picks(limit: Int = 3): List<ShortTermPick> {
        val aiPicks = repository.loadAiPicks(null)
            .filter { it.market == "KR" && it.confidence >= 60 }
            .sortedWith(compareByDescending<com.giwon.signaldesk.features.workspace.application.WorkspaceAiPick> { it.confidence }
                .thenByDescending { it.expectedReturnRate })

        if (aiPicks.isEmpty()) return emptyList()

        // 실시간 시세로 보강 (등락률 반영)
        val quotes = naverClient.fetchKoreanQuotes(aiPicks.map { it.ticker })

        return aiPicks
            .map { pick ->
                val quote = quotes[pick.ticker]
                val adjustedReturn = if (quote != null) {
                    // 오늘 하락 중이면 기대수익률에 소폭 가산 (단타 관점 - 저점 매수 기회)
                    if (quote.changeRate <= -1.5) pick.expectedReturnRate + 1.0
                    else pick.expectedReturnRate
                } else {
                    pick.expectedReturnRate
                }
                ShortTermPick(
                    ticker = pick.ticker,
                    name = pick.name,
                    basis = pick.basis.take(20),
                    confidence = pick.confidence,
                    expectedReturnRate = adjustedReturn,
                )
            }
            .sortedByDescending { it.expectedReturnRate }
            .take(limit)
    }
}
