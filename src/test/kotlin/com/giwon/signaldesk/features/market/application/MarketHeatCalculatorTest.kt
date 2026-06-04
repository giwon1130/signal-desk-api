package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketHeatCalculatorTest {

    private fun section(vararg flows: InvestorFlow) = MarketSection(
        market = "KR", title = "한국", indices = emptyList(),
        sentiment = emptyList(), investorFlows = flows.toList(), leadingStocks = emptyList(),
    )

    private fun flow(investor: String, amount: Double) =
        InvestorFlow(investor = investor, amountBillionWon = amount, note = "", positive = amount >= 0)

    @Test
    fun `flowBias - 외인+기관 대량 순매도면 낮지만 0 에 포화되지 않는다`() {
        // 외국인 -6,996억 + 기관 +2,021억 = -4,975억. 이전 /60 이면 0 포화, 이제 /180 이라 22 안팎.
        val s = section(flow("외국인", -6996.0), flow("기관", 2021.0), flow("개인", 4847.0))
        val score = MarketHeatCalculator.flowBias(s)
        assertTrue(score in 15.0..30.0, "score=$score (강매도라도 0 포화는 안 됨)")
    }

    @Test
    fun `flowBias - 수급 데이터 없으면 50 중립`() {
        assertEquals(50.0, MarketHeatCalculator.flowBias(section()))
    }

    @Test
    fun `flowBias - 대량 순매수는 높지만 100 직전`() {
        val s = section(flow("외국인", 5000.0), flow("기관", 1000.0))  // +6,000 → 50+33=83
        assertTrue(MarketHeatCalculator.flowBias(s) in 75.0..95.0)
    }

    @Test
    fun `flowBiasDetail - 외인·기관 실제 순매수 금액을 보여준다`() {
        val detail = MarketHeatCalculator.flowBiasDetail(section(flow("외국인", -6996.0), flow("기관", 2021.0)))
        assertTrue(detail.contains("외인"), detail)
        assertTrue(detail.contains("6,996"), detail)   // 외인 순매도 금액
        assertTrue(detail.contains("2,021"), detail)   // 기관 순매수 금액
        assertTrue(detail.contains("합산"), detail)
    }

    @Test
    fun `flowBiasDetail - 데이터 없으면 안내 문구`() {
        assertTrue(MarketHeatCalculator.flowBiasDetail(section()).contains("데이터"))
    }
}
