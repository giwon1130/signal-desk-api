package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalContextAnnotatorTest {

    private val annotator = PersonalContextAnnotator()

    // ── annotateRecommendations ───────────────────────────────────────

    @Test
    fun `보유 종목이면 userStatus HELD`() {
        val ai = aiWith(pick = pick("KR", "005930"), log = log("KR", "005930"))
        val portfolio = portfolioOf(position("KR", "005930"))

        val annotated = annotator.annotateRecommendations(ai, emptyList(), portfolio)

        assertEquals("HELD", annotated.picks.first().userStatus)
        assertEquals("HELD", annotated.executionLogs.first().userStatus)
    }

    @Test
    fun `관심 종목이면 userStatus WATCHED`() {
        val ai = aiWith(pick = pick("US", "AAPL"), log = log("US", "AAPL"))
        val watchlist = listOf(watchItem("US", "AAPL"))

        val annotated = annotator.annotateRecommendations(ai, watchlist, emptyPortfolio())

        assertEquals("WATCHED", annotated.picks.first().userStatus)
        assertEquals("WATCHED", annotated.executionLogs.first().userStatus)
    }

    @Test
    fun `보유와 관심에 모두 없으면 NEW`() {
        val ai = aiWith(pick = pick("KR", "000660"), log = log("KR", "000660"))

        val annotated = annotator.annotateRecommendations(ai, emptyList(), emptyPortfolio())

        assertEquals("NEW", annotated.picks.first().userStatus)
        assertEquals("NEW", annotated.executionLogs.first().userStatus)
    }

    @Test
    fun `보유와 관심에 겹치면 HELD 우선`() {
        val ai = aiWith(pick = pick("KR", "005930"), log = log("KR", "005930"))
        val portfolio = portfolioOf(position("KR", "005930"))
        val watchlist = listOf(watchItem("KR", "005930"))

        val annotated = annotator.annotateRecommendations(ai, watchlist, portfolio)

        assertEquals("HELD", annotated.picks.first().userStatus)
    }

    // ── annotateAlternativeSignals ────────────────────────────────────

    @Test
    fun `매칭되는 보유_관심이 없으면 personalImpact null`() {
        val signal = signal(label = "Pentagon Pizza Index", score = 80)
        val result = annotator.annotateAlternativeSignals(listOf(signal), emptyList(), emptyPortfolio())
        assertNull(result.first().personalImpact)
    }

    @Test
    fun `Pentagon 지표 과열 + 방산 보유 = 수혜 가능성`() {
        val signal = signal(label = "Pentagon Pizza Index", score = 75)
        val portfolio = portfolioOf(position("KR", "012450", name = "한화에어로스페이스"))

        val result = annotator.annotateAlternativeSignals(listOf(signal), emptyList(), portfolio)

        val impact = result.first().personalImpact
        assertNotNull(impact)
        assertTrue(impact!!.contains("보유"), "actual=$impact")
        assertTrue(impact.contains("한화에어로스페이스"), "actual=$impact")
        assertTrue(impact.contains("수혜"), "actual=$impact")
    }

    @Test
    fun `Pentagon 지표 과열 + 방산 관심만 = 진입 타이밍 주의`() {
        val signal = signal(label = "Pentagon Pizza Index", score = 80)
        val watchlist = listOf(watchItem("US", "LMT", name = "Lockheed Martin"))

        val result = annotator.annotateAlternativeSignals(listOf(signal), watchlist, emptyPortfolio())

        val impact = result.first().personalImpact
        assertNotNull(impact)
        assertTrue(impact!!.contains("관심"), "actual=$impact")
        assertTrue(impact.contains("진입 타이밍"), "actual=$impact")
    }

    @Test
    fun `지표 점수 낮으면 관망 문구`() {
        val signal = signal(label = "Pentagon Pizza Index", score = 40)
        val portfolio = portfolioOf(position("KR", "012450", name = "한화에어로스페이스"))

        val result = annotator.annotateAlternativeSignals(listOf(signal), emptyList(), portfolio)

        val impact = result.first().personalImpact
        assertNotNull(impact)
        assertTrue(impact!!.contains("관망"), "actual=$impact")
    }

    @Test
    fun `Policy 지표는 금융_제약 업종 매칭`() {
        val signal = signal(label = "Policy Buzz", score = 75)
        val portfolio = portfolioOf(position("KR", "055550", name = "KB금융"))

        val result = annotator.annotateAlternativeSignals(listOf(signal), emptyList(), portfolio)

        assertNotNull(result.first().personalImpact)
    }

    @Test
    fun `알려지지 않은 지표 label은 personalImpact null`() {
        val signal = signal(label = "Unknown Signal", score = 90)
        val portfolio = portfolioOf(position("KR", "005930"))

        val result = annotator.annotateAlternativeSignals(listOf(signal), emptyList(), portfolio)

        assertNull(result.first().personalImpact)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    private fun pick(market: String, ticker: String) = RecommendationPick(
        market = market, ticker = ticker, name = "종목",
        basis = "", confidence = 70, note = "",
        expectedReturnRate = 2.0,
    )

    private fun log(market: String, ticker: String) = RecommendationExecutionLog(
        date = "2026-04-22", market = market, ticker = ticker, name = "종목",
        stage = "RECOMMEND", status = "open", rationale = "",
        confidence = 70, expectedReturnRate = 2.0, realizedReturnRate = null,
    )

    private fun aiWith(pick: RecommendationPick, log: RecommendationExecutionLog) = AIRecommendationSection(
        generatedDate = "2026-04-22", summary = "",
        picks = listOf(pick), trackRecords = emptyList(), executionLogs = listOf(log),
    )

    private fun watchItem(market: String, ticker: String, name: String = "종목", sector: String = "") = WatchItem(
        market = market, ticker = ticker, name = name, price = 10000,
        changeRate = 0.0, sector = sector, stance = "", note = "",
    )

    private fun position(market: String, ticker: String, name: String = "종목") = HoldingPosition(
        market = market, ticker = ticker, name = name,
        buyPrice = 10000, currentPrice = 10200, quantity = 10,
        profitAmount = 2_000L, evaluationAmount = 102_000L, profitRate = 2.0,
    )

    private fun portfolioOf(vararg positions: HoldingPosition) = PortfolioSummary(
        totalCost = positions.sumOf { (it.buyPrice * it.quantity).toLong() },
        totalValue = positions.sumOf { it.evaluationAmount },
        totalProfit = positions.sumOf { it.profitAmount },
        totalProfitRate = 2.0,
        positions = positions.toList(),
    )

    private fun emptyPortfolio() = PortfolioSummary(
        totalCost = 0L, totalValue = 0L, totalProfit = 0L, totalProfitRate = 0.0,
        positions = emptyList(),
    )

    private fun signal(label: String, score: Int) = AlternativeSignal(
        label = label, score = score, state = "", note = "",
        highlights = emptyList(), source = "test", url = "", experimental = true,
    )
}
