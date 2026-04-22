package com.giwon.signaldesk.features.push.application

import java.time.LocalDate
import java.util.UUID

interface PushRepository {
    fun upsertDevice(userId: UUID, platform: String, expoToken: String): PushDevice
    fun deleteDevice(userId: UUID, expoToken: String)
    fun listDevices(userId: UUID): List<PushDevice>
    fun listAllDevicesGroupedByUser(): Map<UUID, List<PushDevice>>

    fun loadRecentAlertLog(date: LocalDate): Set<AlertLogEntry>
    fun recordAlert(userId: UUID, ticker: String, direction: AlertDirection, date: LocalDate, changeRate: Double)
}
