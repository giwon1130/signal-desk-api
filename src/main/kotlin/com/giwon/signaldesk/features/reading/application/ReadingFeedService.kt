package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.reading.domain.CallPerformance
import com.giwon.signaldesk.features.reading.domain.CallStatus
import com.giwon.signaldesk.features.reading.domain.Leader
import com.giwon.signaldesk.features.reading.domain.ReadingCall
import com.giwon.signaldesk.features.reading.domain.ReadingPost
import com.giwon.signaldesk.features.reading.repository.ReadingRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 피드 + 성과 추적 (Phase D). 수익률은 entryPrice(박제) 대비 현재가로 derived.
 *
 * 하락 콜: targetReturnPct 가 음수면 하방 목표. 수익률 부호는 그대로 정직하게 노출(§12).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class ReadingFeedService(
    private val repo: ReadingRepository,
    private val priceService: ReadingPriceService,
    private val planService: com.giwon.signaldesk.features.plan.PlanService,
) {
    data class PostWithCalls(
        val post: ReadingPost,
        val leaderName: String,
        val calls: List<CallPerformance>,
    )

    data class LeaderStats(
        val totalCalls: Int,
        val hitCount: Int,
        val hitRate: Double,        // 0~1
        val avgReturnPct: Double?,  // 활성/전체 콜 평균 (null=계산 불가)
    )

    /** 내 피드 — 구독 리더 + 본인(리더면) 글. 최신순. */
    fun feed(userId: UUID, limit: Int = 50): List<PostWithCalls> {
        var leaderIds = (repo.followingLeaderIds(userId) + listOfNotNull(repo.findLeader(userId)?.userId)).distinct()
        if (leaderIds.isEmpty()) return emptyList()
        // 구독 후 PRO 만료 시 AI 리더 글 노출 차단(접근 시점 라이브 재확인). 본인이 AI는 아님.
        if (!planService.isPro(userId)) {
            val aiLeaderIds = repo.findLeaders(leaderIds).filterValues { it.isAi }.keys
            if (aiLeaderIds.isNotEmpty()) leaderIds = leaderIds.filter { it !in aiLeaderIds }
        }
        if (leaderIds.isEmpty()) return emptyList()
        val posts = repo.postsByLeaders(leaderIds, limit)
        return buildPostViews(posts)
    }

    /**
     * 특정 리더의 글 목록 (프로필 화면).
     * FOLLOWERS 공개 글은 구독자/본인에게만 — 비구독자는 PUBLIC 글만 본다(AI 리더 PRO 게이트 + 비공개글 누수 방지).
     */
    fun leaderPosts(leaderUserId: UUID, viewerUserId: UUID?, limit: Int = 50): List<PostWithCalls> {
        // AI 리더의 FOLLOWERS 글은 PRO 전용 — 구독 후 만료한 뷰어는 PUBLIC 만 보이게(접근 시점 재확인).
        val isAiLeader = repo.findLeader(leaderUserId)?.isAi == true
        val proOk = !isAiLeader || (viewerUserId != null && planService.isPro(viewerUserId))
        val canSeeFollowers = proOk && viewerUserId != null &&
            (viewerUserId == leaderUserId || viewerUserId in repo.followerIds(leaderUserId))
        val posts = repo.postsByLeader(leaderUserId, limit)
            .filter { canSeeFollowers || it.visibility == com.giwon.signaldesk.features.reading.domain.PostVisibility.PUBLIC }
        return buildPostViews(posts)
    }

    /** 리더 통계 — 콜 성과 집계. */
    fun leaderStats(leaderUserId: UUID): LeaderStats {
        val calls = repo.callsByLeader(leaderUserId)
        if (calls.isEmpty()) return LeaderStats(0, 0, 0.0, null)
        val hit = calls.count { it.status == CallStatus.HIT }
        // 적중률·평균수익 모두 '결착(HIT+CLOSED)' 콜만 모집단으로 — 진행 중(ACTIVE)의 시세 변동이
        // 지표를 흔들지 않게 일치시킴. 결착 콜은 hitPrice(박제)로 수익률 계산 → 라이브 시세 fetch 불필요
        // (discoverLeaders 가 리더마다 leaderStats 를 부르는데 동기 HTTP 가 N회 나가던 문제 제거).
        val resolvedCalls = calls.filter { it.status == CallStatus.HIT || it.status == CallStatus.CLOSED }
        val perfs = resolvedCalls.mapNotNull { frozenReturnPct(it) }
        val avg = if (perfs.isEmpty()) null else perfs.average()
        return LeaderStats(
            totalCalls = calls.size,
            hitCount = hit,
            hitRate = if (resolvedCalls.isNotEmpty()) hit.toDouble() / resolvedCalls.size else 0.0,
            avgReturnPct = avg,
        )
    }

    private fun buildPostViews(posts: List<ReadingPost>): List<PostWithCalls> {
        if (posts.isEmpty()) return emptyList()
        // 글/리더 단위 in 절 일괄 조회 — 글 50개 피드가 쿼리 50+회를 만들던 N+1 방지.
        val callsByPost = repo.callsByPosts(posts.map { it.id })
        val allCalls = callsByPost.values.flatten()
        val priceMap = priceService.currentPrices(allCalls.map { it.market to it.ticker })
        val leaders = repo.findLeaders(posts.map { it.leaderUserId }.distinct())
        val leaderNames = leaders.mapValues { (_, l) -> l.displayName }
        return posts.map { post ->
            val perfs = (callsByPost[post.id] ?: emptyList()).map { c ->
                toPerformance(c, priceMap[c.market to c.ticker])
            }
            PostWithCalls(post, leaderNames[post.leaderUserId] ?: "리더", perfs)
        }
    }

    /** 결착(HIT/CLOSED) 콜의 박제 수익률 — hitPrice 기반, HTTP 불필요. 레거시(hitPrice null) 행은 null. */
    private fun frozenReturnPct(call: ReadingCall): Double? {
        val price = call.hitPrice ?: return null
        return price.subtract(call.entryPrice)
            .divide(call.entryPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .toDouble()
    }

    private fun toPerformance(call: ReadingCall, currentPrice: BigDecimal?): CallPerformance {
        // HIT/CLOSED 는 결착가(박제)로 수익률 고정 — 결착 후 시세가 흔들려도 성과는 불변(신뢰 핵심).
        val priceForReturn = if (call.status != CallStatus.ACTIVE && call.hitPrice != null) call.hitPrice else currentPrice
        val ret = priceForReturn?.let {
            it.subtract(call.entryPrice)
                .divide(call.entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()
        }
        return CallPerformance(call, priceForReturn, ret)
    }

    fun getLeader(leaderUserId: UUID): Leader? = repo.findLeader(leaderUserId)

    fun followerCount(leaderUserId: UUID): Int = repo.followerCount(leaderUserId)

    data class LeaderCard(
        val userId: UUID,
        val displayName: String,
        val bio: String,
        val followerCount: Int,
        val totalCalls: Int,
        val hitRate: Double,
        val avgReturnPct: Double?,
        val following: Boolean,
        val isAi: Boolean,
    )

    /**
     * 리딩 둘러보기 — 승인된 리더 + 통계. 적중률·구독자 순으로 정렬해 발견성 제공.
     * 본인/이미 구독 중인 리더는 following=true 로 표시(중복 구독 방지 UX).
     */
    fun discoverLeaders(viewerUserId: UUID?): List<LeaderCard> {
        val leaders = repo.listApprovedLeaders()
        if (leaders.isEmpty()) return emptyList()
        val followingIds = viewerUserId?.let { repo.followingLeaderIds(it).toSet() } ?: emptySet()
        return leaders.map { l ->
            val stats = leaderStats(l.userId)
            LeaderCard(
                userId = l.userId, displayName = l.displayName, bio = l.bio,
                followerCount = repo.followerCount(l.userId),
                totalCalls = stats.totalCalls, hitRate = stats.hitRate, avgReturnPct = stats.avgReturnPct,
                following = l.userId == viewerUserId || l.userId in followingIds,
                isAi = l.isAi,
            )
        }.sortedWith(
            // AI 리더를 맨 위로, 그다음 적중률·구독자 순.
            compareByDescending<LeaderCard> { it.isAi }
                .thenByDescending { it.hitRate }
                .thenByDescending { it.followerCount },
        )
    }
}
