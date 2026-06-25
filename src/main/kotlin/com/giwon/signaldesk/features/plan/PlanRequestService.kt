package com.giwon.signaldesk.features.plan

import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.UserRepository
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * PRO 신청 — 결제 전의 수동 전환 퍼널.
 * 신청 → 운영자 푸시 → 콘솔 승인(plan=PRO + 사용자에게 완료 푸시) / 보류.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class PlanRequestService(
    private val jdbc: JdbcTemplate,
    private val userRepository: UserRepository,
    private val pushRepository: PushRepository,
    private val expoPushClient: ExpoPushClient,
    private val adminGuard: AdminGuard,
    @org.springframework.beans.factory.annotation.Value("\${signal-desk.admin.emails:gwim113000@gmail.com}") adminEmailsRaw: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val adminEmails = adminEmailsRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    data class MyRequest(val status: String?)
    data class PendingRequest(
        val userId: String, val email: String, val nickname: String,
        val plan: String, val requestedAt: String,
    )

    /** 신청 — 이미 PRO 면 거절, 기존 신청(보류 포함)은 PENDING 으로 되살림. */
    fun request(userId: UUID): String {
        val user = userRepository.findById(userId) ?: throw IllegalStateException("유저를 찾을 수 없습니다.")
        if (user.plan.equals("PRO", ignoreCase = true)) return "이미 PRO 플랜이에요!"
        jdbc.update(
            """
            insert into signal_desk_plan_requests (user_id, status, requested_at) values (?::uuid, 'PENDING', now())
            on conflict (user_id) do update set status = 'PENDING', requested_at = now(), resolved_at = null
            """.trimIndent(),
            userId.toString(),
        )
        notifyAdmins(user.nickname, user.email)
        log.info("plan request — user={} ({})", userId.toString().take(8), user.email)
        return "신청이 접수됐어요! PRO는 베타 준비 중이라 지금 바로 승인되진 않고, 정식 오픈 때 순차적으로 안내드릴게요."
    }

    fun myStatus(userId: UUID): MyRequest =
        MyRequest(
            jdbc.query(
                "select status from signal_desk_plan_requests where user_id = ?::uuid",
                { rs, _ -> rs.getString("status") }, userId.toString(),
            ).firstOrNull(),
        )

    fun pending(): List<PendingRequest> =
        jdbc.query(
            """
            select r.user_id, r.requested_at, u.email, u.nickname, u.plan
            from signal_desk_plan_requests r
            join signal_desk_users u on u.id = r.user_id
            where r.status = 'PENDING'
            order by r.requested_at asc
            """.trimIndent(),
        ) { rs, _ ->
            PendingRequest(
                userId = rs.getString("user_id"),
                email = rs.getString("email"),
                nickname = rs.getString("nickname"),
                plan = rs.getString("plan") ?: "FREE",
                requestedAt = rs.getTimestamp("requested_at").toInstant().toString(),
            )
        }

    /** 승인 — plan=PRO 전환 + 신청 종결 + 사용자에게 완료 푸시. */
    fun approve(userId: UUID): Boolean {
        val updated = jdbc.update("update signal_desk_users set plan = 'PRO' where id = ?::uuid", userId.toString())
        if (updated == 0) return false
        jdbc.update(
            "update signal_desk_plan_requests set status = 'APPROVED', resolved_at = now() where user_id = ?::uuid",
            userId.toString(),
        )
        runCatching {
            val messages = pushRepository.listDevices(userId).map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = "💎 PRO 전환 완료!",
                    body = "이제 시데 AI에게 하루 100번까지 물어볼 수 있어요.",
                    data = mapOf("type" to "PLAN_APPROVED"),
                    userId = userId,
                )
            }
            if (messages.isNotEmpty()) expoPushClient.send(messages)
        }
        log.info("plan request approved — user={}", userId.toString().take(8))
        return true
    }

    fun dismiss(userId: UUID): Boolean =
        jdbc.update(
            "update signal_desk_plan_requests set status = 'DISMISSED', resolved_at = now() where user_id = ?::uuid and status = 'PENDING'",
            userId.toString(),
        ) > 0

    /** 운영자 디바이스로 신규 신청 푸시. */
    private fun notifyAdmins(nickname: String, email: String) {
        runCatching {
            val messages = adminEmails
                .mapNotNull { userRepository.findByEmail(it) }
                .flatMap { admin -> pushRepository.listDevices(admin.id) }
                .map { d ->
                    ExpoPushClient.Message(
                        to = d.expoToken,
                        title = "💎 PRO 신청",
                        body = "$nickname ($email) 님이 PRO 를 신청했어요 — 운영 콘솔에서 승인해 주세요.",
                        data = mapOf("type" to "PLAN_REQUEST"),
                    )
                }
            if (messages.isNotEmpty()) expoPushClient.send(messages)
        }.onFailure { log.warn("plan request admin push failed", it) }
    }
}
