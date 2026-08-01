package com.giwon.signaldesk.features.traderbridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.bootstrap.UnauthorizedException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class TraderBridgeService(
    private val repository: TraderBridgeRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val secureRandom = SecureRandom()

    fun createOrRotate(userId: UUID): TraderConnectionCreated {
        val raw = ByteArray(32).also(secureRandom::nextBytes)
        val connectionKey = "sdt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val now = clock.instant()
        val hint = connectionKey.takeLast(8)
        repository.replaceConnection(userId, sha256(connectionKey), hint, now)
        return TraderConnectionCreated(
            connectionKey = connectionKey,
            status = TraderConnectionStatus(
                connected = true,
                secretHint = hint,
                createdAt = now,
            ),
        )
    }

    fun status(userId: UUID): TraderConnectionStatus {
        val connection = repository.findConnection(userId) ?: return TraderConnectionStatus(connected = false)
        val snapshot = repository.loadSnapshotJson(userId)?.let { json ->
            runCatching { objectMapper.readValue(json, TraderSnapshot::class.java) }.getOrNull()
        }
        return TraderConnectionStatus(
            connected = true,
            secretHint = connection.secretHint,
            createdAt = connection.createdAt,
            lastSeenAt = connection.lastSeenAt,
            snapshot = snapshot,
        )
    }

    fun disconnect(userId: UUID) = repository.deleteConnection(userId)

    fun publish(connectionKey: String?, snapshot: TraderSnapshot): TraderConnectionStatus {
        val key = connectionKey?.trim().orEmpty()
        if (!key.startsWith("sdt_") || key.length < 40) throw UnauthorizedException("유효한 trader 연결 키가 필요합니다.")
        val userId = repository.findUserIdBySecretHash(sha256(key))
            ?: throw UnauthorizedException("trader 연결 키가 올바르지 않습니다.")
        validate(snapshot)
        val receivedAt = clock.instant()
        repository.saveSnapshot(userId, objectMapper.writeValueAsString(snapshot), receivedAt)
        return status(userId)
    }

    private fun validate(snapshot: TraderSnapshot) {
        val now = clock.instant()
        require(snapshot.mode in ALLOWED_MODES) { "trader 모드가 올바르지 않습니다." }
        require(!snapshot.asOf.isAfter(now.plus(Duration.ofMinutes(5))) && !snapshot.asOf.isBefore(now.minus(Duration.ofHours(24)))) {
            "snapshot 시각이 올바르지 않습니다."
        }
        require((snapshot.killSwitchReason?.length ?: 0) <= 500) { "킬 스위치 사유가 너무 깁니다." }
        require(snapshot.holdings.size <= 100) { "보유 종목은 최대 100개까지 동기화할 수 있습니다." }
        require(snapshot.orders.size <= 100) { "주문 상태는 최대 100개까지 동기화할 수 있습니다." }
        snapshot.holdings.forEach { holding ->
            requireSymbol(holding.symbol)
            require(holding.market in ALLOWED_MARKETS) { "보유 종목 시장이 올바르지 않습니다." }
            require(holding.currency in ALLOWED_CURRENCIES) { "보유 종목 통화가 올바르지 않습니다." }
            require(isBounded(holding.quantity, allowZero = true) && isBounded(holding.marketValue, allowZero = true)) {
                "보유 수량과 평가금액이 올바르지 않습니다."
            }
        }
        snapshot.orders.forEach { order ->
            require(order.approvalId.length in 1..100) { "주문 승인 ID가 올바르지 않습니다." }
            requireSymbol(order.symbol)
            require(order.market in ALLOWED_MARKETS) { "주문 시장이 올바르지 않습니다." }
            require(order.side in ALLOWED_SIDES) { "주문 방향이 올바르지 않습니다." }
            require(order.status in ALLOWED_STATUSES) { "주문 상태가 올바르지 않습니다." }
            require(isBounded(order.quantity, allowZero = false)) { "주문 수량이 올바르지 않습니다." }
            require(order.reason.length <= 500) { "주문 사유가 너무 깁니다." }
        }
    }

    private fun requireSymbol(symbol: String) {
        require(symbol.matches(SYMBOL_REGEX)) { "종목 코드 형식이 올바르지 않습니다." }
    }

    private fun isBounded(value: BigDecimal, allowZero: Boolean): Boolean =
        value.precision() <= 20 && value.scale() in 0..10 && if (allowZero) value >= BigDecimal.ZERO else value > BigDecimal.ZERO

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val SYMBOL_REGEX = Regex("^[A-Za-z0-9.\\-]{1,20}$")
        private val ALLOWED_MODES = setOf("DRY_RUN", "READ_ONLY", "LIVE")
        private val ALLOWED_MARKETS = setOf("KR", "US")
        private val ALLOWED_CURRENCIES = setOf("KRW", "USD")
        private val ALLOWED_SIDES = setOf("BUY", "SELL")
        private val ALLOWED_STATUSES = setOf(
            "PENDING_APPROVAL", "SUBMITTING", "SUBMITTED", "REJECTED", "EXPIRED", "SUBMISSION_UNCERTAIN",
        )
    }
}
