package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RecommendationMetricsCalculatorTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 4, 22)
    private val clock = Clock.fixed(
        ZonedDateTime.of(today, LocalTime.of(12, 0), seoul).toInstant(),
        seoul,
    )
    private val calculator = RecommendationMetricsCalculator(clock)

    @Test
    fun `빈 트랙 레코드는 null`() {
        assertNull(calculator.compute(emptyList()))
    }

    @Test
    fun `30일 윈도우 밖 레코드만 있으면 null`() {
        val old = record(daysAgo = 45, realizedReturnRate = 5.0, success = true)
        assertNull(calculator.compute(listOf(old)))
    }

    @Test
    fun `hit rate 계산`() {
        val records = listOf(
            record(daysAgo = 2, realizedReturnRate = 3.0, success = true),
            record(daysAgo = 5, realizedReturnRate = 5.0, success = true),
            record(daysAgo = 10, realizedReturnRate = -1.5, success = false),
            record(daysAgo = 20, realizedReturnRate = 2.0, success = true),
        )
        val metrics = calculator.compute(records)
        assertNotNull(metrics)
        assertEquals(4, metrics!!.totalCount)
        assertEquals(3, metrics.successCount)
        assertEquals(0.75, metrics.hitRate, 0.001)
    }

    @Test
    fun `평균_최고_최저 수익률`() {
        val records = listOf(
            record(daysAgo = 2, realizedReturnRate = 10.0, success = true),
            record(daysAgo = 5, realizedReturnRate = -5.0, success = false),
            record(daysAgo = 10, realizedReturnRate = 3.0, success = true),
        )
        val metrics = calculator.compute(records)!!
        assertEquals((10.0 - 5.0 + 3.0) / 3, metrics.averageReturnRate, 0.001)
        assertEquals(10.0, metrics.bestReturnRate, 0.001)
        assertEquals(-5.0, metrics.worstReturnRate, 0.001)
    }

    @Test
    fun `윈도우 밖 레코드는 집계에서 제외`() {
        val records = listOf(
            record(daysAgo = 5, realizedReturnRate = 4.0, success = true),
            record(daysAgo = 60, realizedReturnRate = 100.0, success = true), // 윈도우 밖
        )
        val metrics = calculator.compute(records)!!
        assertEquals(1, metrics.totalCount)
        assertEquals(4.0, metrics.averageReturnRate, 0.001)
    }

    @Test
    fun `잘못된 날짜 포맷은 무시`() {
        val valid = record(daysAgo = 3, realizedReturnRate = 2.5, success = true)
        val invalid = record(dateString = "not-a-date", realizedReturnRate = 99.0, success = true)
        val metrics = calculator.compute(listOf(valid, invalid))!!
        assertEquals(1, metrics.totalCount)
        assertTrue(metrics.averageReturnRate in 2.4..2.6)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    private fun record(
        daysAgo: Int = 5,
        dateString: String = today.minusDays(daysAgo.toLong()).toString(),
        realizedReturnRate: Double,
        success: Boolean,
    ) = RecommendationTrackRecord(
        recommendedDate = dateString,
        market = "KR", ticker = "005930", name = "삼성전자",
        entryPrice = 80000, latestPrice = 80000 + (realizedReturnRate * 800).toInt(),
        realizedReturnRate = realizedReturnRate, success = success,
    )
}
