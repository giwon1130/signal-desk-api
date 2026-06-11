package com.giwon.signaldesk.features.admin

import com.giwon.signaldesk.bootstrap.ForbiddenException
import com.giwon.signaldesk.features.auth.application.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 운영자 판정 — `signal-desk.admin.emails`(콤마 구분, 기본 운영자 1명) 화이트리스트.
 * 운영자 콘솔(/api/v1/admin)과 인증 응답의 admin 플래그가 공유한다.
 */
@Component
class AdminGuard(
    @Value("\${signal-desk.admin.emails:gwim113000@gmail.com}") adminEmailsRaw: String,
    @Autowired(required = false) private val userRepository: UserRepository? = null,
) {
    private val adminEmails = adminEmailsRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    fun isAdminEmail(email: String): Boolean = email.trim().lowercase() in adminEmails

    /** 운영자가 아니면 403. */
    fun requireAdmin(userId: UUID) {
        val user = userRepository?.findById(userId)
            ?: throw ForbiddenException("운영자만 접근할 수 있어요.")
        if (!isAdminEmail(user.email)) throw ForbiddenException("운영자만 접근할 수 있어요.")
    }
}
