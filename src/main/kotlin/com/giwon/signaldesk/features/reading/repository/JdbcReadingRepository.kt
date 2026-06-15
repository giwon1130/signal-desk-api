package com.giwon.signaldesk.features.reading.repository

import com.giwon.signaldesk.features.reading.domain.CallCurrency
import com.giwon.signaldesk.features.reading.domain.CallStatus
import com.giwon.signaldesk.features.reading.domain.Follow
import com.giwon.signaldesk.features.reading.domain.Leader
import com.giwon.signaldesk.features.reading.domain.LeaderStatus
import com.giwon.signaldesk.features.reading.domain.PostVisibility
import com.giwon.signaldesk.features.reading.domain.ReadingCall
import com.giwon.signaldesk.features.reading.domain.ReadingPost
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcReadingRepository(
    private val jdbc: JdbcTemplate,
) : ReadingRepository {

    private val leaderMapper = RowMapper<Leader> { rs, _ ->
        Leader(
            userId = UUID.fromString(rs.getString("user_id")),
            displayName = rs.getString("display_name"),
            bio = rs.getString("bio"),
            inviteCode = rs.getString("invite_code"),
            status = LeaderStatus.valueOf(rs.getString("status")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            isAi = runCatching { rs.getBoolean("is_ai") }.getOrDefault(false),
        )
    }

    private val postMapper = RowMapper<ReadingPost> { rs, _ ->
        ReadingPost(
            id = UUID.fromString(rs.getString("id")),
            leaderUserId = UUID.fromString(rs.getString("leader_user_id")),
            title = rs.getString("title"),
            body = rs.getString("body"),
            visibility = PostVisibility.valueOf(rs.getString("visibility")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private val callMapper = RowMapper<ReadingCall> { rs: ResultSet, _ ->
        ReadingCall(
            id = UUID.fromString(rs.getString("id")),
            postId = UUID.fromString(rs.getString("post_id")),
            leaderUserId = UUID.fromString(rs.getString("leader_user_id")),
            market = rs.getString("market"),
            ticker = rs.getString("ticker"),
            name = rs.getString("name"),
            entryPrice = rs.getBigDecimal("entry_price"),
            entryCurrency = CallCurrency.valueOf(rs.getString("entry_currency")),
            entryLockedAt = rs.getTimestamp("entry_locked_at").toInstant(),
            targetReturnPct = rs.getBigDecimal("target_return_pct"),
            status = CallStatus.valueOf(rs.getString("status")),
            hitAt = rs.getTimestamp("hit_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    // ─── Leader ──────────────────────────────────────────────────────────────
    override fun createLeader(leader: Leader): Leader {
        jdbc.update(
            """
            insert into signal_desk_reading_leader
              (user_id, display_name, bio, invite_code, status, created_at)
            values (?::uuid, ?, ?, ?, ?, ?)
            """.trimIndent(),
            leader.userId.toString(), leader.displayName, leader.bio,
            leader.inviteCode, leader.status.name, Timestamp.from(leader.createdAt),
        )
        return leader
    }

    override fun findLeader(userId: UUID): Leader? =
        jdbc.query("select * from signal_desk_reading_leader where user_id = ?::uuid", leaderMapper, userId.toString())
            .firstOrNull()

    override fun findLeaders(userIds: Collection<UUID>): Map<UUID, Leader> {
        if (userIds.isEmpty()) return emptyMap()
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        return jdbc.query(
            "select * from signal_desk_reading_leader where user_id in ($placeholders)",
            leaderMapper, *userIds.map { it.toString() }.toTypedArray(),
        ).associateBy { it.userId }
    }

    override fun listApprovedLeaders(): List<Leader> =
        jdbc.query("select * from signal_desk_reading_leader where status = 'APPROVED' order by created_at desc", leaderMapper)

    override fun findLeaderByInviteCode(code: String): Leader? =
        jdbc.query("select * from signal_desk_reading_leader where invite_code = ?", leaderMapper, code)
            .firstOrNull()

    override fun updateLeaderStatus(userId: UUID, status: LeaderStatus) {
        jdbc.update(
            "update signal_desk_reading_leader set status = ? where user_id = ?::uuid",
            status.name, userId.toString(),
        )
    }

    // ─── Follow ──────────────────────────────────────────────────────────────
    override fun follow(f: Follow) {
        jdbc.update(
            """
            insert into signal_desk_reading_follow (leader_user_id, follower_user_id, joined_at)
            values (?::uuid, ?::uuid, ?)
            on conflict (leader_user_id, follower_user_id) do nothing
            """.trimIndent(),
            f.leaderUserId.toString(), f.followerUserId.toString(), Timestamp.from(f.joinedAt),
        )
    }

    override fun unfollow(leaderUserId: UUID, followerUserId: UUID) {
        jdbc.update(
            "delete from signal_desk_reading_follow where leader_user_id = ?::uuid and follower_user_id = ?::uuid",
            leaderUserId.toString(), followerUserId.toString(),
        )
    }

    override fun followingLeaderIds(followerUserId: UUID): List<UUID> =
        jdbc.query(
            "select leader_user_id from signal_desk_reading_follow where follower_user_id = ?::uuid",
            { rs, _ -> UUID.fromString(rs.getString("leader_user_id")) },
            followerUserId.toString(),
        )

    override fun followerIds(leaderUserId: UUID): List<UUID> =
        jdbc.query(
            "select follower_user_id from signal_desk_reading_follow where leader_user_id = ?::uuid",
            { rs, _ -> UUID.fromString(rs.getString("follower_user_id")) },
            leaderUserId.toString(),
        )

    override fun followerCount(leaderUserId: UUID): Int =
        jdbc.queryForObject(
            "select count(*) from signal_desk_reading_follow where leader_user_id = ?::uuid",
            Int::class.java, leaderUserId.toString(),
        ) ?: 0

    // ─── Post ────────────────────────────────────────────────────────────────
    override fun createPost(post: ReadingPost): ReadingPost {
        jdbc.update(
            """
            insert into signal_desk_reading_post
              (id, leader_user_id, title, body, visibility, created_at)
            values (?::uuid, ?::uuid, ?, ?, ?, ?)
            """.trimIndent(),
            post.id.toString(), post.leaderUserId.toString(), post.title, post.body,
            post.visibility.name, Timestamp.from(post.createdAt),
        )
        return post
    }

    override fun findPost(id: UUID): ReadingPost? =
        jdbc.query("select * from signal_desk_reading_post where id = ?::uuid", postMapper, id.toString())
            .firstOrNull()

    override fun postsByLeaders(leaderUserIds: Collection<UUID>, limit: Int): List<ReadingPost> {
        if (leaderUserIds.isEmpty()) return emptyList()
        val placeholders = leaderUserIds.joinToString(",") { "?::uuid" }
        val args = leaderUserIds.map { it.toString() }.toTypedArray()
        return jdbc.query(
            "select * from signal_desk_reading_post where leader_user_id in ($placeholders) order by created_at desc limit $limit",
            postMapper, *args,
        )
    }

    override fun postsByLeader(leaderUserId: UUID, limit: Int): List<ReadingPost> =
        jdbc.query(
            "select * from signal_desk_reading_post where leader_user_id = ?::uuid order by created_at desc limit $limit",
            postMapper, leaderUserId.toString(),
        )

    // ─── Call ────────────────────────────────────────────────────────────────
    override fun insertCall(call: ReadingCall): ReadingCall {
        jdbc.update(
            """
            insert into signal_desk_reading_call
              (id, post_id, leader_user_id, market, ticker, name, entry_price, entry_currency,
               entry_locked_at, target_return_pct, status, hit_at, created_at)
            values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            call.id.toString(), call.postId.toString(), call.leaderUserId.toString(),
            call.market, call.ticker, call.name, call.entryPrice, call.entryCurrency.name,
            Timestamp.from(call.entryLockedAt), call.targetReturnPct, call.status.name,
            call.hitAt?.let { Timestamp.from(it) }, Timestamp.from(call.createdAt),
        )
        return call
    }

    override fun callsByPost(postId: UUID): List<ReadingCall> =
        jdbc.query(
            "select * from signal_desk_reading_call where post_id = ?::uuid order by created_at",
            callMapper, postId.toString(),
        )

    override fun callsByPosts(postIds: Collection<UUID>): Map<UUID, List<ReadingCall>> {
        if (postIds.isEmpty()) return emptyMap()
        val placeholders = postIds.joinToString(",") { "?::uuid" }
        return jdbc.query(
            "select * from signal_desk_reading_call where post_id in ($placeholders) order by created_at",
            callMapper, *postIds.map { it.toString() }.toTypedArray(),
        ).groupBy { it.postId }
    }

    override fun callsByLeader(leaderUserId: UUID): List<ReadingCall> =
        jdbc.query(
            "select * from signal_desk_reading_call where leader_user_id = ?::uuid order by created_at desc",
            callMapper, leaderUserId.toString(),
        )

    override fun activeCalls(): List<ReadingCall> =
        jdbc.query("select * from signal_desk_reading_call where status = 'ACTIVE'", callMapper)

    override fun markCallStatus(callId: UUID, status: CallStatus, hitAt: Instant?) {
        jdbc.update(
            "update signal_desk_reading_call set status = ?, hit_at = ? where id = ?::uuid",
            status.name, hitAt?.let { Timestamp.from(it) }, callId.toString(),
        )
    }
}
