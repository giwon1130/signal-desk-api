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

data class DateCount(val date: String, val count: Int)
data class KeyCount(val key: String, val count: Int)
data class TickerCount(val market: String, val ticker: String, val name: String, val count: Int)
