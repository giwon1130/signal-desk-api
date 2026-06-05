package com.giwon.signaldesk.features.push.application

import java.time.LocalDate
import java.util.UUID

data class PushDevice(
    val id: UUID,
    val userId: UUID,
    val platform: String,
    val expoToken: String,
)

enum class AlertDirection { UP, DOWN, PRICE_BELOW, PRICE_ABOVE, VOLUME_SPIKE }

data class AlertLogEntry(
    val userId: UUID,
    val ticker: String,
    val direction: AlertDirection,
    val date: LocalDate,
)

data class AlertHistoryItem(
    val id: String,
    val market: String,
    val ticker: String,
    val name: String,
    val direction: AlertDirection,
    val changeRate: Double,
    val reason: String?,
    val alertDate: String,
    val sentAt: String,
    val readAt: String?,
)

data class AlertCandidate(
    val userId: UUID,
    val ticker: String,
    val name: String,
    val market: String,
    val changeRate: Double,
    val direction: AlertDirection,
    val currentPrice: Int = 0,
    val thresholdPrice: Int? = null,
    val volumeRatio: Double? = null,
)
