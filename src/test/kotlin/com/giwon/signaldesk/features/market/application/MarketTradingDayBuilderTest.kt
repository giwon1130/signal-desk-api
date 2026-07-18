package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketTradingDayBuilderTest {

    @Test
    fun `한국이 주말이고 미국은 전일 장후면 한국 주말 상태를 유지한다`() {
        val result = MarketTradingDayBuilder.build(
            listOf(
                session(market = "KR", note = "주말 휴장"),
                session(market = "US", note = "정규장 종료"),
            ),
        )

        assertTrue(result.isWeekend)
        assertFalse(result.isHoliday)
        assertTrue(result.headline.contains("한국 주말 휴장"))
    }

    @Test
    fun `한국 휴장일에 미국장이 열리면 시장별 상태를 함께 안내한다`() {
        val result = MarketTradingDayBuilder.build(
            listOf(
                session(market = "KR", note = "제헌절 휴장"),
                session(market = "US", note = "정규장 진행 중", isOpen = true),
            ),
        )

        assertFalse(result.isWeekend)
        assertTrue(result.isHoliday)
        assertEquals("한국 휴장일 · 미국장 진행", result.headline)
    }

    private fun session(market: String, note: String, isOpen: Boolean = false) = MarketSessionStatus(
        market = market,
        label = market,
        phase = if (isOpen) "REGULAR" else "CLOSED",
        status = if (isOpen) "정규장" else "휴장",
        isOpen = isOpen,
        localTime = "",
        note = note,
    )
}
