package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyBriefBuilderTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    // ── slot resolution ──────────────────────────────────────────────

    @Test
    fun `주말이면 WEEKEND slot`() {
        val b = builderAt(hour = 11)
        val result = b.build(
            base = baseBriefing(), watchAlerts = emptyList(), portfolio = emptyPortfolio(),
            aiRecommendations = emptyAi(), marketSummary = emptyList(),
            alternativeSignals = emptyList(), tradingDay = tradingDay(weekend = true),
        )
        assertEquals("WEEKEND", result.slot)
    }

    @Test
    fun `휴장일이면 HOLIDAY slot`() {
        val b = builderAt(hour = 11)
        val result = b.build(
            base = baseBriefing(), watchAlerts = emptyList(), portfolio = emptyPortfolio(),
            aiRecommendations = emptyAi(), marketSummary = emptyList(),
            alternativeSignals = emptyList(), tradingDay = tradingDay(holiday = true),
        )
        assertEquals("HOLIDAY", result.slot)
    }

    @Test
    fun `평일 9시 전이면 PRE_MARKET slot`() {
        val b = builderAt(hour = 7, minute = 30)
        val result = buildWith(b)
        assertEquals("PRE_MARKET", result.slot)
    }

    @Test
    fun `평일 9-15_30 사이는 INTRADAY slot`() {
        val b = builderAt(hour = 13)
        val result = buildWith(b)
        assertEquals("INTRADAY", result.slot)
    }

    @Test
    fun `평일 15_30 이후는 POST_MARKET slot`() {
        val b = builderAt(hour = 16)
        val result = buildWith(b)
        assertEquals("POST_MARKET", result.slot)
    }

    // ── narrative / context ──────────────────────────────────────────

    @Test
    fun `narrative는 slot 문구로 시작`() {
        val b = builderAt(hour = 7, minute = 30)
        val result = buildWith(b)
        assertTrue(result.narrative.startsWith("아시아 개장 전입니다."), "actual=${result.narrative}")
    }

    @Test
    fun `보유 없을 때 holdingPnlLabel은 null`() {
        val b = builderAt(hour = 13)
        val result = buildWith(b)
        assertNull(result.context?.holdingPnlLabel)
    }

    @Test
    fun `보유 있으면 holdingPnlLabel 포맷`() {
        val b = builderAt(hour = 13)
        val result = b.build(
            base = baseBriefing(),
            watchAlerts = emptyList(),
            portfolio = PortfolioSummary(
                totalCost = 10_000_000L, totalValue = 10_230_000L,
                totalProfit = 230_000L, totalProfitRate = 2.3, positions = listOf(samplePosition()),
            ),
            aiRecommendations = emptyAi(),
            marketSummary = emptyList(),
            alternativeSignals = emptyList(),
            tradingDay = tradingDay(),
        )
        val label = result.context?.holdingPnlLabel
        assertTrue(label != null && label.contains("+2.30%"), "actual=$label")
    }

    @Test
    fun `actionItems는 최대 3개`() {
        val b = builderAt(hour = 10)
        val alerts = (1..5).map { idx ->
            WatchAlert(
                severity = "medium", category = "price", market = "KR",
                ticker = "0000$idx", name = "종목$idx", title = "가격 변동",
                note = "…", score = 60, tags = listOf("관심종목"),
            )
        }
        val result = b.build(
            base = baseBriefing(), watchAlerts = alerts, portfolio = emptyPortfolio(),
            aiRecommendations = emptyAi(), marketSummary = emptyList(),
            alternativeSignals = emptyList(), tradingDay = tradingDay(),
        )
        assertEquals(3, result.actionItems.size)
    }

    @Test
    fun `marketSummary 기반으로 marketMood 계산`() {
        val b = builderAt(hour = 13)
        val result = b.build(
            base = baseBriefing(), watchAlerts = emptyList(), portfolio = emptyPortfolio(),
            aiRecommendations = emptyAi(),
            marketSummary = listOf(
                SummaryMetric("Fear Meter", 25.0, "공포 확대", ""),
                SummaryMetric("US Heat", 40.0, "혼조", ""),
                SummaryMetric("KR Heat", 40.0, "동반 약세", ""),
            ),
            alternativeSignals = emptyList(), tradingDay = tradingDay(),
        )
        assertEquals("방어적", result.context?.marketMood)
    }

    @Test
    fun `빈 워크스페이스에서도 narrative 생성`() {
        val b = builderAt(hour = 13)
        val result = buildWith(b)
        assertTrue(result.narrative.isNotBlank())
        assertTrue(result.actionItems.isEmpty())
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    private fun builderAt(hour: Int, minute: Int = 0): DailyBriefBuilder {
        val monday = LocalDate.of(2026, 4, 20)
        val at = ZonedDateTime.of(monday, LocalTime.of(hour, minute), seoul)
        return DailyBriefBuilder(clock = Clock.fixed(at.toInstant(), seoul))
    }

    private fun buildWith(b: DailyBriefBuilder) = b.build(
        base = baseBriefing(), watchAlerts = emptyList(), portfolio = emptyPortfolio(),
        aiRecommendations = emptyAi(), marketSummary = emptyList(),
        alternativeSignals = emptyList(), tradingDay = tradingDay(),
    )

    private fun baseBriefing() = DailyBriefing(
        headline = "헤드라인", preMarket = listOf("기본 a"), afterMarket = listOf("기본 b"),
    )

    private fun tradingDay(weekend: Boolean = false, holiday: Boolean = false) = TradingDayStatus(
        krOpen = !weekend && !holiday, usOpen = false,
        isWeekend = weekend, isHoliday = holiday,
        headline = "", nextTradingDay = "", advice = "",
    )

    private fun emptyPortfolio() = PortfolioSummary(
        totalCost = 0L, totalValue = 0L, totalProfit = 0L, totalProfitRate = 0.0,
        positions = emptyList(),
    )

    private fun emptyAi() = AIRecommendationSection(
        generatedDate = "", summary = "", picks = emptyList(),
        trackRecords = emptyList(), executionLogs = emptyList(),
    )

    private fun samplePosition() = HoldingPosition(
        market = "KR", ticker = "005930", name = "삼성전자",
        buyPrice = 80000, currentPrice = 81840, quantity = 100,
        profitAmount = 184_000L, evaluationAmount = 8_184_000L,
        profitRate = 2.3, source = "test",
    )
}
