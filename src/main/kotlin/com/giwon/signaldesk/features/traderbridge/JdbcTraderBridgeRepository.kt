package com.giwon.signaldesk.features.traderbridge

import com.giwon.signaldesk.features.workspace.application.JdbcStoreCondition
import org.springframework.context.annotation.Conditional
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Conditional(JdbcStoreCondition::class)
class JdbcTraderBridgeRepository(
    private val jdbc: JdbcTemplate,
) : TraderBridgeRepository {

    @Transactional
    override fun replaceConnection(userId: UUID, secretHash: String, secretHint: String, now: Instant) {
        jdbc.update(
            """
            insert into signal_desk_trader_connections (user_id, secret_hash, secret_hint, created_at, last_seen_at)
            values (?::uuid, ?, ?, ?, null)
            on conflict (user_id) do update set
              secret_hash = excluded.secret_hash,
              secret_hint = excluded.secret_hint,
              created_at = excluded.created_at,
              last_seen_at = null
            """.trimIndent(),
            userId.toString(), secretHash, secretHint, Timestamp.from(now),
        )
        // 키 회전 뒤 예전 trader 상태가 현재 상태처럼 보이지 않게 초기화한다.
        jdbc.update("delete from signal_desk_trader_snapshots where user_id = ?::uuid", userId.toString())
    }

    override fun findConnection(userId: UUID): TraderConnectionRecord? =
        jdbc.query(
            """
            select user_id, secret_hint, created_at, last_seen_at
            from signal_desk_trader_connections where user_id = ?::uuid
            """.trimIndent(),
            { rs, _ ->
                TraderConnectionRecord(
                    userId = UUID.fromString(rs.getString("user_id")),
                    secretHint = rs.getString("secret_hint"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant(),
                )
            },
            userId.toString(),
        ).firstOrNull()

    override fun findUserIdBySecretHash(secretHash: String): UUID? =
        jdbc.query(
            "select user_id from signal_desk_trader_connections where secret_hash = ?",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) },
            secretHash,
        ).firstOrNull()

    @Transactional
    override fun saveSnapshot(userId: UUID, snapshotJson: String, now: Instant) {
        jdbc.update(
            """
            insert into signal_desk_trader_snapshots (user_id, snapshot_json, updated_at)
            values (?::uuid, ?, ?)
            on conflict (user_id) do update set
              snapshot_json = excluded.snapshot_json,
              updated_at = excluded.updated_at
            """.trimIndent(),
            userId.toString(), snapshotJson, Timestamp.from(now),
        )
        jdbc.update(
            "update signal_desk_trader_connections set last_seen_at = ? where user_id = ?::uuid",
            Timestamp.from(now), userId.toString(),
        )
    }

    override fun loadSnapshotJson(userId: UUID): String? =
        jdbc.query(
            "select snapshot_json from signal_desk_trader_snapshots where user_id = ?::uuid",
            { rs, _ -> rs.getString("snapshot_json") },
            userId.toString(),
        ).firstOrNull()

    @Transactional
    override fun deleteConnection(userId: UUID) {
        jdbc.update("delete from signal_desk_trader_snapshots where user_id = ?::uuid", userId.toString())
        jdbc.update("delete from signal_desk_trader_connections where user_id = ?::uuid", userId.toString())
    }
}
