package com.giwon.signaldesk.features.backtest.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.market.application.HistoryBar
import com.giwon.signaldesk.features.market.application.YahooQuoteClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SeasonalityBacktestServiceTest {
    // computeReport 는 네트워크를 안 타므로 disabled 클라이언트로 충분.
    private val service = SeasonalityBacktestService(YahooQuoteClient(ObjectMapper(), false, "https://x", java.util.concurrent.Executors.newSingleThreadExecutor()))

    /** 1월에만 매 거래일 +0.45%(월 누적 ~+10%), 나머지 달은 flat 인 13년 합성 일봉. */
    private fun janBullBars(): List<HistoryBar> {
        val bars = ArrayList<HistoryBar>()
        var price = 100.0
        var d = LocalDate.of(2012, 1, 1)
        val end = LocalDate.of(2024, 12, 31)
        while (!d.isAfter(end)) {
            if (d.dayOfWeek.value <= 5) { // 평일만
                if (d.monthValue == 1) price *= 1.0045
                bars.add(HistoryBar(d, price))
            }
            d = d.plusDays(1)
        }
        return bars
    }

    @Test
    fun `1월 강세 합성데이터 — 1월 STRONG·승률 100·BUY 하이라이트`() {
        val r = service.computeReport(janBullBars(), "US", "TEST", "테스트", 0.10)
        assertNotNull(r)
        assertEquals(12, r!!.monthly.size)
        val jan = r.monthly.first { it.month == 1 }
        assertTrue(jan.meanPct in 8.0..12.0, "jan.mean=${jan.meanPct}")
        assertEquals(100.0, jan.winRatePct, 0.01, "jan.winRate=${jan.winRatePct}")
        assertEquals("STRONG", jan.tier)
        assertTrue(jan.sampleYears >= 10, "n=${jan.sampleYears}")
        // 비용(0.1%) 빼도 +10% 유효 → BUY 하이라이트
        assertTrue(r.highlights.any { it.kind == "BUY_MONTH" && it.month == 1 }, "highlights=${r.highlights}")
    }

    @Test
    fun `표본 부족(250봉 미만)이면 null`() {
        val few = (1..100).map { HistoryBar(LocalDate.of(2024, 1, 1).plusDays(it.toLong()), 100.0) }
        assertNull(service.computeReport(few, "US", "X", "X", 0.1))
    }
}
