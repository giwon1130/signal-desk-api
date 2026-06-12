package com.giwon.signaldesk.features.plan

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 요금제(FREE/PRO) 판정 + FREE 자원 상한의 단일 진입점.
 *
 * plan 조회는 PK 단건이라 저렴해 캐시 없이 매번 읽는다(승인 즉시 반영 보장).
 * 알림 발송처럼 다수 사용자 plan 이 필요하면 [plansFor] 배치로 N+1 을 피한다.
 *
 * 상한은 "신규 추가"에만 적용 — 기존 row 삭제·미발송 없음(grandfather). 호출부에서
 * "이미 존재하는 키(=수정)면 카운트하지 말 것".
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
open class PlanService(
    private val jdbc: JdbcTemplate,
    @Value("\${signal-desk.plan.free.watchlist:20}") private val watchlistLimit: Int,
    @Value("\${signal-desk.plan.free.holdings:10}") private val holdingsLimit: Int,
    @Value("\${signal-desk.plan.free.leader-subscriptions:3}") private val leaderSubLimit: Int,
    @Value("\${signal-desk.plan.free.leagues:1}") private val leagueLimit: Int,
) {
    enum class Resource { WATCHLIST, HOLDINGS, LEADER_SUBSCRIPTIONS, LEAGUES }

    open fun planOf(userId: UUID): String =
        jdbc.query(
            "select plan from signal_desk_users where id = ?::uuid",
            { rs, _ -> rs.getString("plan") ?: "FREE" },
            userId.toString(),
        ).firstOrNull() ?: "FREE"

    open fun isPro(userId: UUID): Boolean = planOf(userId).equals("PRO", ignoreCase = true)

    /** 다수 사용자 plan 일괄 조회 (알림 수신자 필터용). */
    fun plansFor(userIds: Set<UUID>): Map<UUID, String> {
        if (userIds.isEmpty()) return emptyMap()
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        return jdbc.query(
            "select id, plan from signal_desk_users where id in ($placeholders)",
            { rs, _ -> UUID.fromString(rs.getString("id")) to (rs.getString("plan") ?: "FREE") },
            *userIds.map { it.toString() }.toTypedArray(),
        ).toMap()
    }

    fun freeLimitFor(resource: Resource): Int = when (resource) {
        Resource.WATCHLIST -> watchlistLimit
        Resource.HOLDINGS -> holdingsLimit
        Resource.LEADER_SUBSCRIPTIONS -> leaderSubLimit
        Resource.LEAGUES -> leagueLimit
    }

    /**
     * FREE 사용자가 [currentCount] 개를 이미 보유한 상태에서 1개 더 추가할 수 있는지.
     * PRO 면 무조건 통과. 초과 시 IllegalArgumentException → 전역 핸들러가 400 + 한국어 메시지.
     * 신규 추가일 때만 호출할 것(기존 키 수정은 카운트 제외).
     */
    fun assertCanAdd(userId: UUID, resource: Resource, currentCount: Int) {
        if (isPro(userId)) return
        val limit = freeLimitFor(resource)
        require(currentCount < limit) { limitMessage(resource, limit) }
    }

    private fun limitMessage(resource: Resource, limit: Int): String = when (resource) {
        Resource.WATCHLIST -> "관심 종목은 FREE 플랜에서 최대 ${limit}개까지예요. PRO 로 업그레이드하면 무제한이에요. 💎"
        Resource.HOLDINGS -> "보유 종목은 FREE 플랜에서 최대 ${limit}개까지예요. PRO 로 업그레이드하면 무제한이에요. 💎"
        Resource.LEADER_SUBSCRIPTIONS -> "리더 구독은 FREE 플랜에서 최대 ${limit}명까지예요. PRO 로 업그레이드하면 무제한이에요. 💎"
        Resource.LEAGUES -> "진행 중인 리그는 FREE 플랜에서 최대 ${limit}개까지 만들 수 있어요. PRO 로 업그레이드하면 무제한이에요. 💎"
    }
}
