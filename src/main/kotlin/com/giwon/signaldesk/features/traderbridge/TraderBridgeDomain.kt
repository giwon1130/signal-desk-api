package com.giwon.signaldesk.features.traderbridge

import java.math.BigDecimal
import java.time.Instant

data class TraderHoldingSnapshot(
    val symbol: String,
    val market: String,
    val quantity: BigDecimal,
    val marketValue: BigDecimal,
    val currency: String,
)

data class TraderOrderSnapshot(
    val approvalId: String,
    val symbol: String,
    val market: String,
    val side: String,
    val quantity: BigDecimal,
    val status: String,
    val reason: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val submittedAt: Instant? = null,
)

/**
 * 개인 trader가 Signal Desk에 올리는 최소 상태. 계좌번호·토스 토큰·client secret은 받지 않는다.
 */
data class TraderSnapshot(
    val asOf: Instant,
    val mode: String,
    val killSwitchEnabled: Boolean,
    val killSwitchReason: String? = null,
    val holdings: List<TraderHoldingSnapshot> = emptyList(),
    val orders: List<TraderOrderSnapshot> = emptyList(),
)

data class TraderConnectionRecord(
    val userId: java.util.UUID,
    val secretHint: String,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
)

data class TraderConnectionStatus(
    val connected: Boolean,
    val secretHint: String? = null,
    val createdAt: Instant? = null,
    val lastSeenAt: Instant? = null,
    val snapshot: TraderSnapshot? = null,
)

data class TraderConnectionCreated(
    /** 한 번만 반환되는 Signal Desk 전용 연결 키. */
    val connectionKey: String,
    val status: TraderConnectionStatus,
)
