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
    /** 일괄 조회 — 피드 렌더에서 리더별 단건 조회 N+1 방지. */
    fun findLeaders(userIds: Collection<UUID>): Map<UUID, Leader>
    fun findLeaderByInviteCode(code: String): Leader?
    /** 승인된 리더 전체 — 리딩 둘러보기(리더 발견)용. */
    fun listApprovedLeaders(): List<Leader>
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
    /** 일괄 조회 — 피드 렌더에서 글별 단건 조회 N+1 방지. */
    fun callsByPosts(postIds: Collection<UUID>): Map<UUID, List<ReadingCall>>
    fun callsByLeader(leaderUserId: UUID): List<ReadingCall>
    fun activeCalls(): List<ReadingCall>
    /** 콜 상태/결착 갱신 — entry_price 는 불변, hitPrice 는 HIT/CLOSED 결착가 박제. */
    fun markCallStatus(callId: UUID, status: CallStatus, hitAt: Instant?, hitPrice: java.math.BigDecimal? = null)
}
