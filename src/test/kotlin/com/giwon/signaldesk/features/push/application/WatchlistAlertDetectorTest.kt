package com.giwon.signaldesk.features.push.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class WatchlistAlertDetectorTest {

    private val detector = WatchlistAlertDetector()
    private val user = UUID.randomUUID()
    private val today = LocalDate.of(2026, 4, 22)

    private fun row(ticker: String, changeRate: Double) = WatchlistAlertDetector.WatchRow(
        userId = user, market = "KR", ticker = ticker, name = ticker, changeRate = changeRate,
    )

    @Test
    fun `5퍼 미만 변화율은 후보 아님`() {
        val result = detector.detect(listOf(row("005930", 4.9), row("035420", -4.9)), emptySet(), today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `5퍼 이상 상승이면 UP 후보`() {
        val result = detector.detect(listOf(row("005930", 5.0)), emptySet(), today)
        assertEquals(1, result.size)
        assertEquals(AlertDirection.UP, result[0].direction)
    }

    @Test
    fun `5퍼 이상 하락이면 DOWN 후보`() {
        val result = detector.detect(listOf(row("005930", -6.2)), emptySet(), today)
        assertEquals(AlertDirection.DOWN, result[0].direction)
    }

    @Test
    fun `이미 오늘 발송된 조합은 제외`() {
        val sent = setOf(AlertLogEntry(user, "005930", AlertDirection.UP, today))
        val result = detector.detect(listOf(row("005930", 7.0)), sent, today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `같은 종목 반대 방향은 별개 후보로 간주`() {
        val sent = setOf(AlertLogEntry(user, "005930", AlertDirection.UP, today))
        val result = detector.detect(listOf(row("005930", -5.5)), sent, today)
        assertEquals(1, result.size)
        assertEquals(AlertDirection.DOWN, result[0].direction)
    }

    private fun rowWithAlerts(
        ticker: String, currentPrice: Int,
        alertBelow: Int? = null, alertAbove: Int? = null,
        volumeAlert: Boolean = false, volumeRatio: Double? = null,
    ) = WatchlistAlertDetector.WatchRow(
        userId = user, market = "KR", ticker = ticker, name = ticker, changeRate = 0.0,
        currentPrice = currentPrice, alertBelow = alertBelow, alertAbove = alertAbove,
        volumeAlert = volumeAlert, volumeRatio = volumeRatio,
    )

    @Test
    fun `현재가가 하한가 이하이면 PRICE_BELOW 후보`() {
        val result = detector.detect(listOf(rowWithAlerts("005930", 70000, alertBelow = 75000)), emptySet(), today)
        assertEquals(1, result.size)
        assertEquals(AlertDirection.PRICE_BELOW, result[0].direction)
    }

    @Test
    fun `현재가가 하한가 초과이면 후보 아님`() {
        val result = detector.detect(listOf(rowWithAlerts("005930", 80000, alertBelow = 75000)), emptySet(), today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `현재가가 상한가 이상이면 PRICE_ABOVE 후보`() {
        val result = detector.detect(listOf(rowWithAlerts("005930", 90000, alertAbove = 85000)), emptySet(), today)
        assertEquals(1, result.size)
        assertEquals(AlertDirection.PRICE_ABOVE, result[0].direction)
    }

    @Test
    fun `거래량 급증 알림 켜져있고 3배 이상이면 VOLUME_SPIKE`() {
        val result = detector.detect(listOf(rowWithAlerts("005930", 80000, volumeAlert = true, volumeRatio = 3.5)), emptySet(), today)
        assertEquals(1, result.size)
        assertEquals(AlertDirection.VOLUME_SPIKE, result[0].direction)
    }

    @Test
    fun `거래량 알림 꺼져있으면 급증해도 후보 아님`() {
        val result = detector.detect(listOf(rowWithAlerts("005930", 80000, volumeAlert = false, volumeRatio = 5.0)), emptySet(), today)
        assertTrue(result.isEmpty())
    }
}
