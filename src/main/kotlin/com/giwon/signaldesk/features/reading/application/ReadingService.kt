package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.reading.domain.CallStatus
import com.giwon.signaldesk.features.reading.domain.Follow
import com.giwon.signaldesk.features.reading.domain.Leader
import com.giwon.signaldesk.features.reading.domain.LeaderStatus
import com.giwon.signaldesk.features.reading.domain.PostVisibility
import com.giwon.signaldesk.features.reading.domain.ReadingCall
import com.giwon.signaldesk.features.reading.domain.ReadingPost
import com.giwon.signaldesk.features.reading.repository.ReadingRepository
import com.giwon.signaldesk.features.auth.application.UserRepository
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
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
    private val users: UserRepository,
    private val pushRepository: PushRepository,
    private val expoPushClient: ExpoPushClient,
    @Value("\${signal-desk.reading.admin-emails:gwim113000@gmail.com}") private val adminEmailsProp: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 자동 승인 + 승인 권한을 가진 운영자 이메일 집합 (config, 기본 운영자 1명 고정). */
    private val adminEmails: Set<String> by lazy {
        adminEmailsProp.split(",").mapNotNull { it.trim().lowercase().takeIf(String::isNotBlank) }.toSet()
    }

    /** 해당 userId 의 이메일이 운영자 목록에 있는지. */
    private fun isAdmin(userId: UUID): Boolean {
        val email = users.findById(userId)?.email?.lowercase() ?: return false
        return email in adminEmails
    }

    /** 리더가 될 수 있는 권한이 있는지 (현재는 운영자 목록 기준). 앱의 '리더 되기' 노출 판단용. */
    fun canLead(userId: UUID): Boolean = isAdmin(userId)

    // ─── 리더 ────────────────────────────────────────────────────────────────
    /** 리더 신청. 이미 있으면 기존 반환. 운영자는 즉시 APPROVED. */
    fun applyForLeader(userId: UUID, displayName: String, bio: String): Leader {
        repo.findLeader(userId)?.let { return it }
        val status = if (isAdmin(userId)) LeaderStatus.APPROVED else LeaderStatus.PENDING
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
        require(isAdmin(adminUserId)) { "no permission to approve" }
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

    /** 둘러보기에서 코드 없이 userId 로 직접 구독 (공개 리더만). */
    fun subscribeByLeaderId(followerUserId: UUID, leaderUserId: UUID): Leader {
        val leader = repo.findLeader(leaderUserId) ?: error("leader not found")
        require(leader.status == LeaderStatus.APPROVED) { "leader not available" }
        require(leader.userId != followerUserId) { "cannot follow yourself" }
        repo.follow(Follow(leader.userId, followerUserId, Instant.now()))
        log.info("reading subscribe(byId) — follower={} leader={}", followerUserId, leader.userId)
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

        runCatching { notifyFollowersOfNewPost(userId, post, locked) }
            .onFailure { log.warn("reading post push failed: {}", it.message) }
        return post to locked
    }

    /** 새 리딩 게시 → 팔로워에게 푸시 (리더 본인은 제외 — 본인이 막 쓴 글). */
    private fun notifyFollowersOfNewPost(leaderUserId: UUID, post: ReadingPost, calls: List<ReadingCall>) {
        val followerIds = repo.followerIds(leaderUserId)
        if (followerIds.isEmpty()) return
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        val targets = devicesByUser.filterKeys { it in followerIds }
        if (targets.isEmpty()) return

        val leaderName = repo.findLeader(leaderUserId)?.displayName ?: "리더"
        val title = "📣 ${leaderName}님의 새 리딩"
        val callsHint = if (calls.isNotEmpty()) " · ${calls.size}개 종목 콜" else ""
        val body = "${post.title}$callsHint"
        val messages = targets.flatMap { (_, devices) ->
            devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf(
                        "type" to "READING_POST_NEW",
                        "postId" to post.id.toString(),
                        "leaderUserId" to leaderUserId.toString(),
                    ),
                )
            }
        }
        expoPushClient.send(messages)
        log.info("reading post push dispatched — leader={} post={} followers={} messages={}",
            leaderUserId, post.id, targets.size, messages.size)
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
