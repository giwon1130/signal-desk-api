package com.giwon.signaldesk.features.push.application

import java.time.LocalDate
import java.util.UUID

data class PushDevice(
    val id: UUID,
    val userId: UUID,
    val platform: String,
    val expoToken: String,
)

enum class AlertDirection { UP, DOWN }

data class AlertLogEntry(
    val userId: UUID,
    val ticker: String,
    val direction: AlertDirection,
    val date: LocalDate,
)

data class AlertHistoryItem(
    val market: String,
    val ticker: String,
    val name: String,
    val direction: AlertDirection,
    val changeRate: Double,
    val alertDate: String,
    val sentAt: String,
)

data class AlertCandidate(
    val userId: UUID,
    val ticker: String,
    val name: String,
    val market: String,
    val changeRate: Double,
    val direction: AlertDirection,
)
