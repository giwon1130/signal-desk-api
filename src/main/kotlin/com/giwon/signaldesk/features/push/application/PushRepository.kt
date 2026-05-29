package com.giwon.signaldesk.features.push.application

import java.time.LocalDate
import java.util.UUID

interface PushRepository {
    fun upsertDevice(userId: UUID, platform: String, expoToken: String): PushDevice
    fun deleteDevice(userId: UUID, expoToken: String)
    fun listDevices(userId: UUID): List<PushDevice>
    fun listAllDevicesGroupedByUser(): Map<UUID, List<PushDevice>>

    fun loadRecentAlertLog(date: LocalDate): Set<AlertLogEntry>
    fun recordAlert(
        userId: UUID, market: String, ticker: String, name: String,
        direction: AlertDirection, date: LocalDate, changeRate: Double,
    )
    fun listAlertHistory(userId: UUID, limit: Int): List<AlertHistoryItem>

    /**
     * 급등/급락 단계적 재알림용 — 최근 sinceDate 이후 (user,ticker,direction) 별
     * 마지막 알림 시점의 abs(changeRate) 최대값. 현재 변동이 이 값 + STEP 이상일 때만 재알림.
     */
    fun loadRecentAlertedRates(sinceDate: LocalDate): Map<AlertRateKey, Double>

    /** 목표가/손절 도달 알림 1회 발송 후 해당 alert 설정 자동 해제 (alert_above/alert_below = null). */
    fun clearPriceAlert(userId: UUID, ticker: String, clearAbove: Boolean)

    /** 최근 N일 알림 발송 통계 (전체 사용자 합산). */
    fun alertStats(days: Int): AlertStats
}

data class AlertStats(
    val totalCount: Int,
    val uniqueUsers: Int,
    val uniqueTickers: Int,
    val byDate: List<DateCount>,
    val byMarket: List<KeyCount>,
    val byDirection: List<KeyCount>,
    val topTickers: List<TickerCount>,
)

/** 급등/급락 단계 비교 key — (userId, ticker, direction). */
data class AlertRateKey(val userId: UUID, val ticker: String, val direction: AlertDirection)

data class DateCount(val date: String, val count: Int)
data class KeyCount(val key: String, val count: Int)
data class TickerCount(val market: String, val ticker: String, val name: String, val count: Int)
