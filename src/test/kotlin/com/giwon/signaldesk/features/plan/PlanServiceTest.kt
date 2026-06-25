package com.giwon.signaldesk.features.plan

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * FREE 자원 상한 로직 회귀 보호.
 * isPro 를 오버라이드해 DB 없이 PRO/FREE 분기와 상한 경계를 검증한다.
 */
class PlanServiceTest {

    private val uid = UUID.randomUUID()

    private fun service(pro: Boolean) = object : PlanService(
        mock(JdbcTemplate::class.java), watchlistLimit = 20, holdingsLimit = 10, leaderSubLimit = 3, leagueLimit = 1,
        proWatchlistLimit = 100, proHoldingsLimit = 50, proLeaderSubLimit = 30, proLeagueLimit = 5,
    ) {
        override fun isPro(userId: UUID) = pro
    }

    @Test
    fun `freeLimitFor 는 config 값을 반환`() {
        val svc = service(pro = false)
        assertThat(svc.freeLimitFor(PlanService.Resource.WATCHLIST)).isEqualTo(20)
        assertThat(svc.freeLimitFor(PlanService.Resource.HOLDINGS)).isEqualTo(10)
        assertThat(svc.freeLimitFor(PlanService.Resource.LEADER_SUBSCRIPTIONS)).isEqualTo(3)
        assertThat(svc.freeLimitFor(PlanService.Resource.LEAGUES)).isEqualTo(1)
    }

    @Test
    fun `FREE 는 상한 도달 시 추가 거부`() {
        val svc = service(pro = false)
        assertThatThrownBy { svc.assertCanAdd(uid, PlanService.Resource.WATCHLIST, 20) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { svc.assertCanAdd(uid, PlanService.Resource.LEAGUES, 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `FREE 는 상한 미만이면 통과`() {
        val svc = service(pro = false)
        assertThatCode { svc.assertCanAdd(uid, PlanService.Resource.WATCHLIST, 19) }.doesNotThrowAnyException()
        assertThatCode { svc.assertCanAdd(uid, PlanService.Resource.LEAGUES, 0) }.doesNotThrowAnyException()
    }

    @Test
    fun `PRO 는 넉넉한 상한 미만이면 통과`() {
        val svc = service(pro = true)
        assertThatCode { svc.assertCanAdd(uid, PlanService.Resource.WATCHLIST, 99) }.doesNotThrowAnyException()
        assertThatCode { svc.assertCanAdd(uid, PlanService.Resource.LEAGUES, 4) }.doesNotThrowAnyException()
    }

    @Test
    fun `PRO 도 넉넉한 상한(남용 방지) 도달 시 거부`() {
        val svc = service(pro = true)
        assertThatThrownBy { svc.assertCanAdd(uid, PlanService.Resource.WATCHLIST, 100) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { svc.assertCanAdd(uid, PlanService.Resource.LEAGUES, 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
