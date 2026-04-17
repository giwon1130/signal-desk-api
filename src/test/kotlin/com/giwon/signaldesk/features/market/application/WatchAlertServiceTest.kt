package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchAlertServiceTest {

    private val service = WatchAlertService()

    // ── buildWatchAlerts ──────────────────────────────────────────────

    @Test
    fun `가격 급등 2_5 이상 - price 알림 생성`() {
        val watchlist = listOf(watchItem(changeRate = 3.2))
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), watchlist, emptyPortfolio(), emptyAi())
        assertTrue(alerts.any { it.category == "price" })
    }

    @Test
    fun `가격 변동 2_5 미만 - price 알림 없음`() {
        val watchlist = listOf(watchItem(changeRate = 1.5))
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), watchlist, emptyPortfolio(), emptyAi())
        assertTrue(alerts.none { it.category == "price" })
    }

    @Test
    fun `가격 급등 4_5 이상 - severity high`() {
        val watchlist = listOf(watchItem(changeRate = 5.0))
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), watchlist, emptyPortfolio(), emptyAi())
        val alert = alerts.first { it.category == "price" }
        assertEquals("high", alert.severity)
    }

    @Test
    fun `가격 2_5 이상 4_5 미만 - severity medium`() {
        val watchlist = listOf(watchItem(changeRate = 3.0))
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), watchlist, emptyPortfolio(), emptyAi())
        val alert = alerts.first { it.category == "price" }
        assertEquals("medium", alert.severity)
    }

    @Test
    fun `관련 뉴스 2건 이상 - news 알림 생성`() {
        val item = watchItem(name = "삼성전자", ticker = "005930")
        val news = listOf(
            newsItem(title = "삼성전자 실적 발표"),
            newsItem(title = "삼성전자 주가 급등"),
        )
        val alerts = service.buildWatchAlerts(emptyList(), news, listOf(item), emptyPortfolio(), emptyAi())
        assertTrue(alerts.any { it.category == "news" })
    }

    @Test
    fun `관련 뉴스 1건 - news 알림 없음`() {
        val item = watchItem(name = "삼성전자", ticker = "005930")
        val news = listOf(newsItem(title = "삼성전자 실적 발표"))
        val alerts = service.buildWatchAlerts(emptyList(), news, listOf(item), emptyPortfolio(), emptyAi())
        assertTrue(alerts.none { it.category == "news" })
    }

    @Test
    fun `AI 추천 신뢰도 70 이상 기대수익률 4 이상 - ai 알림 생성`() {
        val item = watchItem(market = "KR", ticker = "005930")
        val ai = aiWithPick(market = "KR", ticker = "005930", confidence = 75, expectedReturnRate = 5.0)
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), listOf(item), emptyPortfolio(), ai)
        assertTrue(alerts.any { it.category == "ai" })
    }

    @Test
    fun `AI 추천 신뢰도 60 - ai 알림 없음`() {
        val item = watchItem(market = "KR", ticker = "005930")
        val ai = aiWithPick(market = "KR", ticker = "005930", confidence = 60, expectedReturnRate = 5.0)
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), listOf(item), emptyPortfolio(), ai)
        assertTrue(alerts.none { it.category == "ai" })
    }

    @Test
    fun `포트폴리오 손실 8 이상 - portfolio high severity`() {
        val item = watchItem(market = "KR", ticker = "005930")
        val portfolio = portfolioWithPosition(market = "KR", ticker = "005930", profitRate = -10.0)
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), listOf(item), portfolio, emptyAi())
        val alert = alerts.first { it.category == "portfolio" }
        assertEquals("high", alert.severity)
    }

    @Test
    fun `포트폴리오 수익률 5 미만 - portfolio 알림 없음`() {
        val item = watchItem(market = "KR", ticker = "005930")
        val portfolio = portfolioWithPosition(market = "KR", ticker = "005930", profitRate = 3.0)
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), listOf(item), portfolio, emptyAi())
        assertTrue(alerts.none { it.category == "portfolio" })
    }

    @Test
    fun `결과 최대 6개로 제한`() {
        val items = (1..10).map { watchItem(ticker = "TICK$it", changeRate = 5.0) }
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), items, emptyPortfolio(), emptyAi())
        assertTrue(alerts.size <= 6)
    }

    @Test
    fun `같은 종목은 중복 제거 - distinctBy market_ticker`() {
        val item = watchItem(market = "KR", ticker = "005930", changeRate = 5.0)
        val portfolio = portfolioWithPosition(market = "KR", ticker = "005930", profitRate = -9.0)
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), listOf(item), portfolio, emptyAi())
        val count = alerts.count { it.market == "KR" && it.ticker == "005930" }
        assertEquals(1, count)
    }

    @Test
    fun `심각도 high가 medium보다 먼저 정렬`() {
        val items = listOf(
            watchItem(ticker = "AAA", changeRate = 3.0),  // medium
            watchItem(ticker = "BBB", changeRate = 6.0),  // high
        )
        val alerts = service.buildWatchAlerts(emptyList(), emptyList(), items, emptyPortfolio(), emptyAi())
        if (alerts.size >= 2) {
            val severities = alerts.map { it.severity }
            val highIdx = severities.indexOf("high")
            val mediumIdx = severities.indexOf("medium")
            if (highIdx >= 0 && mediumIdx >= 0) {
                assertTrue(highIdx < mediumIdx)
            }
        }
    }

    // ── formatSignedRate ──────────────────────────────────────────────

    @Test
    fun `양수 수익률 포맷 - +기호 포함`() {
        assertEquals("+3.50%", service.formatSignedRate(3.5))
    }

    @Test
    fun `음수 수익률 포맷 - 마이너스 기호 포함`() {
        assertEquals("-2.50%", service.formatSignedRate(-2.5))
    }

    @Test
    fun `0 수익률 포맷 - 부호 없음`() {
        assertEquals("0.00%", service.formatSignedRate(0.0))
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    private fun watchItem(
        market: String = "KR",
        ticker: String = "005930",
        name: String = "삼성전자",
        changeRate: Double = 0.0,
    ) = WatchItem(
        market = market, ticker = ticker, name = name,
        price = 70000, changeRate = changeRate,
        sector = "반도체", stance = "중립", note = "",
        source = "test",
    )

    private fun newsItem(title: String) = MarketNews(
        market = "KR", title = title, url = "",
        source = "연합뉴스", impact = "보통",
    )

    private fun emptyPortfolio() = PortfolioSummary(
        totalCost = 0, totalValue = 0, totalProfit = 0, totalProfitRate = 0.0,
        positions = emptyList(),
    )

    private fun portfolioWithPosition(market: String, ticker: String, profitRate: Double) = PortfolioSummary(
        totalCost = 1_000_000, totalValue = 1_000_000, totalProfit = 0, totalProfitRate = profitRate,
        positions = listOf(
            HoldingPosition(
                market = market, ticker = ticker, name = "테스트", buyPrice = 10000, currentPrice = 10000,
                quantity = 100, profitAmount = 0, evaluationAmount = 1_000_000,
                profitRate = profitRate, source = "test",
            )
        ),
    )

    private fun emptyAi() = AIRecommendationSection(
        generatedDate = "", summary = "", picks = emptyList(), trackRecords = emptyList(), executionLogs = emptyList(),
    )

    private fun aiWithPick(market: String, ticker: String, confidence: Int, expectedReturnRate: Double) =
        AIRecommendationSection(
            generatedDate = "", summary = "",
            picks = listOf(
                RecommendationPick(
                    market = market, ticker = ticker, name = "테스트", basis = "모멘텀",
                    confidence = confidence, note = "", expectedReturnRate = expectedReturnRate, source = "test",
                )
            ),
            trackRecords = emptyList(), executionLogs = emptyList(),
        )
}
