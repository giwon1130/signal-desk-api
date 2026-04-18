package com.giwon.signaldesk.features.kakao

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.StockSearchService
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import com.giwon.signaldesk.features.workspace.application.WorkspaceService
import org.springframework.stereotype.Service

@Service
class KakaoSkillService(
    private val parser: KakaoMessageParser,
    private val stockSearch: StockSearchService,
    private val repository: SignalDeskWorkspaceRepository,
    private val workspaceService: WorkspaceService,
    private val naverClient: NaverFinanceQuoteClient,
    private val sellSignalService: SellSignalService,
    private val shortTermPickService: ShortTermPickService,
    private val alertStore: KakaoAlertStore,
) {

    fun handle(utterance: String): KakaoSkillResponse {
        return when (parser.parse(utterance)) {
            KakaoIntent.SAVE_TRADE -> handleSaveTrade(utterance)
            KakaoIntent.PORTFOLIO -> handlePortfolio()
            KakaoIntent.SELL_CHECK -> handleSellCheck()
            KakaoIntent.RECOMMEND -> handleRecommend()
            KakaoIntent.DAILY_REPORT -> handleDailyReport()
            KakaoIntent.UNKNOWN -> kakaoText(
                "이렇게 말해봐 👇\n" +
                "• 삼성전자 70000에 샀어\n" +
                "• 내 현황\n" +
                "• 체크 (매도 시기)\n" +
                "• 추천\n" +
                "• 오늘 리포트"
            )
        }
    }

    // ── 매수 저장 ─────────────────────────────────────────────────────────

    private fun handleSaveTrade(utterance: String): KakaoSkillResponse {
        val parsed = parser.parseTrade(utterance)
            ?: return kakaoText("입력 형식을 확인해줘.\n예: 삼성전자 70000에 샀어")

        val results = stockSearch.search(parsed.query, "KR", 1)
        val stock = results.firstOrNull()
            ?: return kakaoText("'${parsed.query}' 종목을 찾지 못했어.")

        val saved = workspaceService.savePortfolioPosition(
            userId = null,
            id = "",
            market = stock.market,
            ticker = stock.ticker,
            name = stock.name,
            buyPrice = parsed.price,
            currentPrice = parsed.price,
            quantity = parsed.quantity,
        )

        return kakaoText(
            "✅ 저장했어!\n\n" +
            "${saved.name} (${saved.ticker})\n" +
            "매수가: ${formatPrice(saved.buyPrice)}원 × ${saved.quantity}주\n" +
            "매수금액: ${formatPrice(saved.buyPrice * saved.quantity)}원"
        )
    }

    // ── 포트폴리오 현황 ───────────────────────────────────────────────────

    private fun handlePortfolio(): KakaoSkillResponse {
        val positions = repository.loadPortfolioPositions(null).filter { it.market == "KR" }
        if (positions.isEmpty()) return kakaoText("아직 저장된 종목이 없어.\n'삼성전자 70000에 샀어' 처럼 입력해봐.")

        val tickers = positions.map { it.ticker }
        val quotes = naverClient.fetchKoreanQuotes(tickers)

        val lines = positions.map { pos ->
            val currentPrice = quotes[pos.ticker]?.currentPrice ?: pos.currentPrice
            val profitRate = if (pos.buyPrice == 0) 0.0
            else ((currentPrice - pos.buyPrice).toDouble() / pos.buyPrice) * 100
            val arrow = if (profitRate >= 0) "▲" else "▼"
            "${pos.name}: ${formatRate(profitRate)} $arrow"
        }

        val totalBuy = positions.sumOf { it.buyPrice * it.quantity }
        val totalCurrent = positions.sumOf {
            val price = quotes[it.ticker]?.currentPrice ?: it.currentPrice
            price * it.quantity
        }
        val totalRate = if (totalBuy == 0) 0.0
        else ((totalCurrent - totalBuy).toDouble() / totalBuy) * 100

        return kakaoText(
            "📊 내 포트폴리오\n\n" +
            lines.joinToString("\n") +
            "\n\n총 수익률: ${formatRate(totalRate)}"
        )
    }

    // ── 매도 시기 체크 ────────────────────────────────────────────────────

    private fun handleSellCheck(): KakaoSkillResponse {
        val signals = sellSignalService.evaluate()
        if (signals.isEmpty()) return kakaoText("저장된 국내 종목이 없어.")

        val lines = signals.map { sig ->
            val emoji = when (sig.level) {
                SellLevel.STRONG_SELL -> "🔴"
                SellLevel.CAUTION -> "🟡"
                SellLevel.TAKE_PROFIT -> "💰"
                SellLevel.HOLD -> "🟢"
            }
            "$emoji ${sig.name}: ${sig.reason}"
        }

        return kakaoText("📋 매도 시기 체크\n\n" + lines.joinToString("\n"))
    }

    // ── 단타 추천 ─────────────────────────────────────────────────────────

    private fun handleRecommend(): KakaoSkillResponse {
        val picks = shortTermPickService.picks(limit = 3)
        if (picks.isEmpty()) return kakaoText("지금은 추천 종목이 없어.\nAI 픽스를 먼저 등록해줘.")

        val lines = picks.mapIndexed { i, pick ->
            "${i + 1}. ${pick.name} (${pick.ticker})\n" +
            "   신뢰도 ${pick.confidence} / 기대수익 ${formatRate(pick.expectedReturnRate)}\n" +
            "   근거: ${pick.basis}"
        }

        return kakaoText("💡 단타 추천 (국내 Top ${picks.size})\n\n" + lines.joinToString("\n\n"))
    }

    // ── 일일 리포트 ───────────────────────────────────────────────────────

    private fun handleDailyReport(): KakaoSkillResponse {
        val report = alertStore.load()

        val sell = sellSignalService.evaluate()
        val picks = shortTermPickService.picks(3)

        val sellLines = if (sell.isEmpty()) "보유 종목 없음" else sell.joinToString("\n") { sig ->
            val emoji = when (sig.level) {
                SellLevel.STRONG_SELL -> "🔴"
                SellLevel.CAUTION -> "🟡"
                SellLevel.TAKE_PROFIT -> "💰"
                SellLevel.HOLD -> "🟢"
            }
            "$emoji ${sig.name} ${formatRate(sig.profitRate)}"
        }

        val pickLines = if (picks.isEmpty()) "없음" else picks.joinToString("\n") { p ->
            "• ${p.name} — 신뢰도 ${p.confidence} / ${formatRate(p.expectedReturnRate)}"
        }

        val timestamp = report?.generatedAt ?: "현재"
        return kakaoText(
            "📅 일일 리포트 ($timestamp)\n\n" +
            "[ 매도 체크 ]\n$sellLines\n\n" +
            "[ 단타 추천 ]\n$pickLines"
        )
    }

    // ── 포맷 헬퍼 ─────────────────────────────────────────────────────────

    private fun formatPrice(price: Int): String = "%,d".format(price)

    private fun formatRate(rate: Double): String =
        if (rate >= 0) "+${"%.1f".format(rate)}%" else "${"%.1f".format(rate)}%"
}
