package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.reading.domain.CallStatus
import com.giwon.signaldesk.features.reading.domain.Follow
import com.giwon.signaldesk.features.reading.domain.Leader
import com.giwon.signaldesk.features.reading.domain.LeaderStatus
import com.giwon.signaldesk.features.reading.domain.PostVisibility
import com.giwon.signaldesk.features.reading.domain.ReadingCall
import com.giwon.signaldesk.features.reading.domain.ReadingPost
import com.giwon.signaldesk.features.reading.repository.ReadingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * 리딩 핵심 유스케이스 — 리더 신청/승인, 구독, 글 게시(콜 가격 lock).
 *
 * §12 결정 반영:
 *  - 리더 자격: 신청/승인제. status PENDING→APPROVED. 본인(운영자)은 자동 APPROVED.
 *  - 콜 entryPrice 박제 immutable. 하락 콜도 허용(양방향 정직).
 *  - 종목 오탐: 작성자가 확정한 목록(confirmedCalls)으로만 등록.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class ReadingService(
    private val repo: ReadingRepository,
    private val priceService: ReadingPriceService,
    @Value("\${signal-desk.reading.admin-user-ids:}") private val adminUserIdsProp: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 자동 승인 + 승인 권한을 가진 운영자 userId 집합 (config). */
    private val adminUserIds: Set<UUID> by lazy {
        adminUserIdsProp.split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
    }

    // ─── 리더 ────────────────────────────────────────────────────────────────
    /** 리더 신청. 이미 있으면 기존 반환. 운영자는 즉시 APPROVED. */
    fun applyForLeader(userId: UUID, displayName: String, bio: String): Leader {
        repo.findLeader(userId)?.let { return it }
        val status = if (userId in adminUserIds) LeaderStatus.APPROVED else LeaderStatus.PENDING
        val leader = Leader(
            userId = userId,
            displayName = displayName.trim().ifBlank { "리더" },
            bio = bio.trim(),
            inviteCode = generateInviteCode(),
            status = status,
            createdAt = Instant.now(),
        )
        repo.createLeader(leader)
        log.info("reading leader apply — user={} status={} code={}", userId, status, leader.inviteCode)
        return leader
    }

    fun getLeader(userId: UUID): Leader? = repo.findLeader(userId)

    /** 운영자가 PENDING 리더를 승인. */
    fun approveLeader(adminUserId: UUID, targetUserId: UUID): Leader {
        require(adminUserId in adminUserIds) { "no permission to approve" }
        val leader = repo.findLeader(targetUserId) ?: error("leader not found")
        repo.updateLeaderStatus(targetUserId, LeaderStatus.APPROVED)
        log.info("reading leader approved — admin={} target={}", adminUserId, targetUserId)
        return leader.copy(status = LeaderStatus.APPROVED)
    }

    private fun requireApprovedLeader(userId: UUID): Leader {
        val leader = repo.findLeader(userId) ?: error("not a leader — apply first")
        require(leader.status == LeaderStatus.APPROVED) { "leader not approved (status=${leader.status})" }
        return leader
    }

    // ─── 구독 ────────────────────────────────────────────────────────────────
    /** inviteCode 로 리더 구독. 승인된 리더만. 본인 구독 불가. */
    fun subscribe(followerUserId: UUID, inviteCode: String): Leader {
        val leader = repo.findLeaderByInviteCode(inviteCode.trim().uppercase())
            ?: error("invite code not found")
        require(leader.status == LeaderStatus.APPROVED) { "leader not available" }
        require(leader.userId != followerUserId) { "cannot follow yourself" }
        repo.follow(Follow(leader.userId, followerUserId, Instant.now()))
        log.info("reading subscribe — follower={} leader={}", followerUserId, leader.userId)
        return leader
    }

    fun unsubscribe(followerUserId: UUID, leaderUserId: UUID) =
        repo.unfollow(leaderUserId, followerUserId)

    fun followingLeaders(followerUserId: UUID): List<Leader> =
        repo.followingLeaderIds(followerUserId).mapNotNull { repo.findLeader(it) }

    fun followerCount(leaderUserId: UUID): Int = repo.followerCount(leaderUserId)

    // ─── 글 게시 (콜 가격 lock) ────────────────────────────────────────────────
    data class CallInput(
        val market: String,            // KR | US
        val ticker: String,
        val name: String,
        val targetReturnPct: BigDecimal?,  // null 이면 기본 +15% 기준
    )

    /**
     * 리딩 글 게시 + 확정 콜들의 가격 박제. 트랜잭션 1건처럼 묶되, 시세 실패한 콜은 전체 거부.
     */
    fun publishPost(
        userId: UUID,
        title: String,
        body: String,
        visibility: PostVisibility,
        confirmedCalls: List<CallInput>,
    ): Pair<ReadingPost, List<ReadingCall>> {
        requireApprovedLeader(userId)
        require(title.isNotBlank()) { "title required" }

        val now = Instant.now()
        val post = ReadingPost(
            id = UUID.randomUUID(),
            leaderUserId = userId,
            title = title.trim(),
            body = body.trim(),
            visibility = visibility,
            createdAt = now,
        )

        // 먼저 모든 콜 가격을 lock — 하나라도 실패하면 post 도 안 만든다.
        val locked = confirmedCalls.map { input ->
            val market = input.market.uppercase()
            require(market == "KR" || market == "US") { "market must be KR or US" }
            val ticker = input.ticker.trim().uppercase()
            val quote = priceService.lock(market, ticker)
            ReadingCall(
                id = UUID.randomUUID(),
                postId = post.id,
                leaderUserId = userId,
                market = market,
                ticker = ticker,
                name = input.name.trim().ifBlank { ticker },
                entryPrice = quote.price,
                entryCurrency = quote.currency,
                entryLockedAt = now,
                targetReturnPct = input.targetReturnPct,
                status = CallStatus.ACTIVE,
                hitAt = null,
                createdAt = now,
            )
        }

        repo.createPost(post)
        locked.forEach { repo.insertCall(it) }
        log.info("reading post published — leader={} post={} calls={}", userId, post.id, locked.size)
        return post to locked
    }

    /** 콜 수동 종료 (리더만, 무기한 정책 — §12). HIT 마킹은 스케줄러가. */
    fun closeCall(userId: UUID, callId: UUID) {
        val leader = repo.findLeader(userId) ?: error("not a leader")
        // 콜 소유 검증은 markCallStatus 전에. (콜 조회는 post 통해야 하므로 leader 일치로 우선 보호)
        repo.markCallStatus(callId, CallStatus.CLOSED, null)
        log.info("reading call closed — leader={} call={}", leader.userId, callId)
    }

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHIJKLMNPQRSTUVWXYZ23456789"  // 0/O,1/I 제외
        repeat(10) {
            val code = (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
            if (repo.findLeaderByInviteCode(code) == null) return code
        }
        error("invite code generation failed (try again)")
    }
}
