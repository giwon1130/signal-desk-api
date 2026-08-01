package com.giwon.signaldesk.features.traderbridge

import java.time.Instant
import java.util.UUID

interface TraderBridgeRepository {
    fun replaceConnection(userId: UUID, secretHash: String, secretHint: String, now: Instant)
    fun findConnection(userId: UUID): TraderConnectionRecord?
    fun findUserIdBySecretHash(secretHash: String): UUID?
    fun saveSnapshot(userId: UUID, snapshotJson: String, now: Instant)
    fun loadSnapshotJson(userId: UUID): String?
    fun deleteConnection(userId: UUID)
}
