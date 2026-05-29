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
        val leaderIds = (repo.followingLeaderIds(userId) + listOfNotNull(repo.findLeader(userId)?.userId)).distinct()
        if (leaderIds.isEmpty()) return emptyList()
        val posts = repo.postsByLeaders(leaderIds, limit)
        return buildPostViews(posts)
    }

    /** 특정 리더의 글 목록 (프로필 화면). */
    fun leaderPosts(leaderUserId: UUID, limit: Int = 50): List<PostWithCalls> =
        buildPostViews(repo.postsByLeader(leaderUserId, limit))

    /** 리더 통계 — 콜 성과 집계. */
    fun leaderStats(leaderUserId: UUID): LeaderStats {
        val calls = repo.callsByLeader(leaderUserId)
        if (calls.isEmpty()) return LeaderStats(0, 0, 0.0, null)
        val hit = calls.count { it.status == CallStatus.HIT }
        val perfs = withPerformance(calls).mapNotNull { it.returnPct }
        val avg = if (perfs.isEmpty()) null else perfs.average()
        return LeaderStats(
            totalCalls = calls.size,
            hitCount = hit,
            hitRate = hit.toDouble() / calls.size,
            avgReturnPct = avg,
        )
    }

    private fun buildPostViews(posts: List<ReadingPost>): List<PostWithCalls> {
        if (posts.isEmpty()) return emptyList()
        val callsByPost = posts.associate { it.id to repo.callsByPost(it.id) }
        val allCalls = callsByPost.values.flatten()
        val priceMap = priceService.currentPrices(allCalls.map { it.market to it.ticker })
        val leaderNames = posts.map { it.leaderUserId }.distinct()
            .associateWith { repo.findLeader(it)?.displayName ?: "리더" }
        return posts.map { post ->
            val perfs = (callsByPost[post.id] ?: emptyList()).map { c ->
                toPerformance(c, priceMap[c.market to c.ticker])
            }
            PostWithCalls(post, leaderNames[post.leaderUserId] ?: "리더", perfs)
        }
    }

    private fun withPerformance(calls: List<ReadingCall>): List<CallPerformance> {
        val priceMap = priceService.currentPrices(calls.map { it.market to it.ticker })
        return calls.map { toPerformance(it, priceMap[it.market to it.ticker]) }
    }

    private fun toPerformance(call: ReadingCall, currentPrice: BigDecimal?): CallPerformance {
        val ret = currentPrice?.let {
            it.subtract(call.entryPrice)
                .divide(call.entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .toDouble()
        }
        return CallPerformance(call, currentPrice, ret)
    }

    fun getLeader(leaderUserId: UUID): Leader? = repo.findLeader(leaderUserId)

    fun followerCount(leaderUserId: UUID): Int = repo.followerCount(leaderUserId)
}
