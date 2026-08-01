package com.giwon.signaldesk.features.traderbridge

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.bootstrap.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TraderBridgeServiceTest {
    private val now = Instant.parse("2026-08-01T10:00:00Z")
    private val repository = FakeTraderBridgeRepository()
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val service = TraderBridgeService(repository, mapper, Clock.fixed(now, ZoneOffset.UTC))
    private val userId = UUID.fromString("2d8e6b56-88a5-4c66-a4d6-c8348c443aaa")

    @Test
    fun `연결 키는 한 번만 반환하고 저장소에는 해시만 남긴다`() {
        val created = service.createOrRotate(userId)

        assertTrue(created.connectionKey.startsWith("sdt_"))
        assertEquals(created.connectionKey.takeLast(8), created.status.secretHint)
        assertNotEquals(created.connectionKey, repository.secretHash)
        assertEquals(64, repository.secretHash?.length)
        assertEquals(null, service.status(userId).snapshot)
    }

    @Test
    fun `유효한 연결 키로 읽기 전용 snapshot을 저장한다`() {
        val key = service.createOrRotate(userId).connectionKey

        val status = service.publish(key, validSnapshot())

        assertTrue(status.connected)
        assertEquals("DRY_RUN", status.snapshot?.mode)
        assertEquals(now, status.lastSeenAt)
        assertEquals(1, status.snapshot?.holdings?.size)
    }

    @Test
    fun `잘못된 키와 과도한 snapshot을 거절한다`() {
        service.createOrRotate(userId)
        assertThrows(UnauthorizedException::class.java) { service.publish("sdt_invalid", validSnapshot()) }

        val key = service.createOrRotate(userId).connectionKey
        val oversized = validSnapshot().copy(holdings = List(101) { validSnapshot().holdings.first() })
        assertThrows(IllegalArgumentException::class.java) { service.publish(key, oversized) }
    }

    @Test
    fun `연결 해제 시 키와 snapshot을 모두 지운다`() {
        val key = service.createOrRotate(userId).connectionKey
        service.publish(key, validSnapshot())

        service.disconnect(userId)

        assertFalse(service.status(userId).connected)
        assertEquals(null, repository.snapshotJson)
    }

    private fun validSnapshot() = TraderSnapshot(
        asOf = now,
        mode = "DRY_RUN",
        killSwitchEnabled = false,
        holdings = listOf(
            TraderHoldingSnapshot("005930", "KR", BigDecimal("2"), BigDecimal("140000"), "KRW"),
        ),
        orders = listOf(
            TraderOrderSnapshot(
                approvalId = "approval-1",
                symbol = "005930",
                market = "KR",
                side = "BUY",
                quantity = BigDecimal.ONE,
                status = "PENDING_APPROVAL",
                reason = "DRY_RUN 검토",
                createdAt = now,
                expiresAt = now.plusSeconds(300),
            ),
        ),
    )
}

private class FakeTraderBridgeRepository : TraderBridgeRepository {
    var userId: UUID? = null
    var secretHash: String? = null
    var hint: String? = null
    var createdAt: Instant? = null
    var lastSeenAt: Instant? = null
    var snapshotJson: String? = null

    override fun replaceConnection(userId: UUID, secretHash: String, secretHint: String, now: Instant) {
        this.userId = userId
        this.secretHash = secretHash
        hint = secretHint
        createdAt = now
        lastSeenAt = null
        snapshotJson = null
    }

    override fun findConnection(userId: UUID): TraderConnectionRecord? =
        if (this.userId == userId && secretHash != null) {
            TraderConnectionRecord(userId, requireNotNull(hint), requireNotNull(createdAt), lastSeenAt)
        } else null

    override fun findUserIdBySecretHash(secretHash: String): UUID? =
        userId?.takeIf { this.secretHash == secretHash }

    override fun saveSnapshot(userId: UUID, snapshotJson: String, now: Instant) {
        require(this.userId == userId)
        this.snapshotJson = snapshotJson
        lastSeenAt = now
    }

    override fun loadSnapshotJson(userId: UUID): String? = snapshotJson.takeIf { this.userId == userId }

    override fun deleteConnection(userId: UUID) {
        if (this.userId == userId) {
            this.userId = null
            secretHash = null
            hint = null
            createdAt = null
            lastSeenAt = null
            snapshotJson = null
        }
    }
}
