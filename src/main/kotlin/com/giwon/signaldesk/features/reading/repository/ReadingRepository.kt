package com.giwon.signaldesk.features.reading.repository

import com.giwon.signaldesk.features.reading.domain.CallStatus
import com.giwon.signaldesk.features.reading.domain.Follow
import com.giwon.signaldesk.features.reading.domain.Leader
import com.giwon.signaldesk.features.reading.domain.LeaderStatus
import com.giwon.signaldesk.features.reading.domain.ReadingCall
import com.giwon.signaldesk.features.reading.domain.ReadingPost
import java.time.Instant
import java.util.UUID

/**
 * 리딩 CRUD. 구현 [JdbcReadingRepository] — jdbc mode 만.
 * ReadingCall 은 entry_price immutable — 본 인터페이스에 call update(가격) 없음.
 */
interface ReadingRepository {
    // Leader
    fun createLeader(leader: Leader): Leader
    fun findLeader(userId: UUID): Leader?
    fun findLeaderByInviteCode(code: String): Leader?
    fun updateLeaderStatus(userId: UUID, status: LeaderStatus)

    // Follow
    fun follow(f: Follow)
    fun unfollow(leaderUserId: UUID, followerUserId: UUID)
    fun followingLeaderIds(followerUserId: UUID): List<UUID>
    fun followerIds(leaderUserId: UUID): List<UUID>
    fun followerCount(leaderUserId: UUID): Int

    // Post
    fun createPost(post: ReadingPost): ReadingPost
    fun findPost(id: UUID): ReadingPost?
    fun postsByLeaders(leaderUserIds: Collection<UUID>, limit: Int = 50): List<ReadingPost>
    fun postsByLeader(leaderUserId: UUID, limit: Int = 50): List<ReadingPost>

    // Call
    fun insertCall(call: ReadingCall): ReadingCall
    fun callsByPost(postId: UUID): List<ReadingCall>
    fun callsByLeader(leaderUserId: UUID): List<ReadingCall>
    fun activeCalls(): List<ReadingCall>
    /** 콜 상태/HIT 시각만 갱신 (entry_price 등 가격 정보는 불변). */
    fun markCallStatus(callId: UUID, status: CallStatus, hitAt: Instant?)
}
