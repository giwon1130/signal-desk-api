package com.giwon.signaldesk.features.admin

import com.giwon.signaldesk.common.KST
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * 운영자 콘솔 API — admin.emails 화이트리스트 계정만 (그 외 403).
 *   GET   /api/v1/admin/overview        — 핵심 지표 한눈에
 *   GET   /api/v1/admin/users           — 사용자 목록 (plan/가입일/오늘 질문 수)
 *   PATCH /api/v1/admin/users/{id}/plan — FREE ↔ PRO 전환 (결제 인프라 전 수동 운영)
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AdminController(
    private val jdbc: JdbcTemplate,
    private val adminGuard: AdminGuard,
    private val authContext: AuthContext,
    private val planRequestService: com.giwon.signaldesk.features.plan.PlanRequestService,
) {
    data class Overview(
        val totalUsers: Int,
        val proUsers: Int,
        val pushDevices: Int,
        val watchItems: Int,
        val portfolioPositions: Int,
        val assistantQuestionsToday: Int,
        val alertsSentToday: Int,
    )

    data class AdminUser(
        val id: String,
        val email: String,
        val nickname: String,
        val plan: String,
        val createdAt: String,
        val questionsToday: Int,
    )

    data class PlanRequest(val plan: String = "FREE")

    @GetMapping("/overview")
    fun overview(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<Overview> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        val today = java.sql.Date.valueOf(LocalDate.now(KST))
        fun count(sql: String, vararg args: Any): Int =
            jdbc.queryForObject(sql, Int::class.java, *args) ?: 0
        return ApiResponse(true, Overview(
            totalUsers = count("select count(*) from signal_desk_users"),
            proUsers = count("select count(*) from signal_desk_users where plan = 'PRO'"),
            pushDevices = count("select count(*) from signal_desk_push_devices"),
            watchItems = count("select count(*) from signal_desk_watchlist where user_id is not null"),
            portfolioPositions = count("select count(*) from signal_desk_portfolio_positions where user_id is not null"),
            assistantQuestionsToday = count("select coalesce(sum(question_count), 0) from signal_desk_assistant_usage where usage_date = ?", today),
            alertsSentToday = count("select count(*) from signal_desk_push_alert_log where alert_date = ?", today),
        ))
    }

    @GetMapping("/users")
    fun users(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<AdminUser>> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        val today = java.sql.Date.valueOf(LocalDate.now(KST))
        val rows = jdbc.query(
            """
            select u.id, u.email, u.nickname, u.plan, u.created_at,
                   coalesce(a.question_count, 0) as questions_today
            from signal_desk_users u
            left join signal_desk_assistant_usage a on a.user_id = u.id and a.usage_date = ?
            order by u.created_at desc
            limit 200
            """.trimIndent(),
            { rs, _ ->
                AdminUser(
                    id = rs.getString("id"),
                    email = rs.getString("email"),
                    nickname = rs.getString("nickname"),
                    plan = rs.getString("plan") ?: "FREE",
                    createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                    questionsToday = rs.getInt("questions_today"),
                )
            }, today,
        )
        return ApiResponse(true, rows)
    }

    /** PRO 신청 대기 목록. */
    @GetMapping("/plan-requests")
    fun planRequests(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<List<com.giwon.signaldesk.features.plan.PlanRequestService.PendingRequest>> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        return ApiResponse(true, planRequestService.pending())
    }

    /** 신청 승인 — plan=PRO + 사용자에게 완료 푸시. */
    @org.springframework.web.bind.annotation.PostMapping("/plan-requests/{userId}/approve")
    fun approvePlanRequest(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable userId: String,
    ): ApiResponse<Boolean> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        return ApiResponse(true, planRequestService.approve(UUID.fromString(userId)))
    }

    /** 신청 보류 — 목록에서 제거 (사용자는 재신청 가능). */
    @org.springframework.web.bind.annotation.PostMapping("/plan-requests/{userId}/dismiss")
    fun dismissPlanRequest(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable userId: String,
    ): ApiResponse<Boolean> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        return ApiResponse(true, planRequestService.dismiss(UUID.fromString(userId)))
    }

    @PatchMapping("/users/{id}/plan")
    fun changePlan(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
        @RequestBody req: PlanRequest,
    ): ApiResponse<Boolean> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        val plan = req.plan.uppercase()
        require(plan == "FREE" || plan == "PRO") { "plan 은 FREE 또는 PRO 여야 합니다." }
        val updated = jdbc.update(
            "update signal_desk_users set plan = ? where id = ?::uuid",
            plan, UUID.fromString(id).toString(),
        )
        return ApiResponse(true, updated > 0)
    }
}
