package com.giwon.signaldesk.features.disclosure.presentation

import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.auth.application.AuthException
import com.giwon.signaldesk.features.disclosure.application.DartDisclosureService
import com.giwon.signaldesk.features.disclosure.application.Disclosure
import com.giwon.signaldesk.features.disclosure.application.DisclosureSeenCleanupService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/disclosures")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DartDisclosureController(
    private val service: DartDisclosureService,
    private val cleanupService: DisclosureSeenCleanupService,
    private val adminGuard: AdminGuard,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    /** 운영자 전용 가드 — 미인증/비운영자 거부. */
    private fun requireAdmin(auth: String?) {
        val ctx = authContext ?: throw AuthException("로그인이 필요해요.")
        adminGuard.requireAdmin(ctx.requireUserId(auth))
    }

    /** 보유/관심 KR 종목의 최근 공시. */
    @GetMapping("/recent")
    fun recent(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(required = false, defaultValue = "30") limit: Int,
    ): Map<String, Any> {
        val userId = authContext?.optionalUserId(auth)
            ?: return mapOf("success" to true, "data" to mapOf("disclosures" to emptyList<Disclosure>()))
        val list = service.listRecentForUser(userId, limit.coerceIn(1, 100))
        return mapOf("success" to true, "data" to mapOf("disclosures" to list))
    }

    /** 운영용 수동 트리거 — 운영자 전용(익명 호출 시 DART 쿼터 소진/푸시 트리거 abuse 방지). */
    @PostMapping("/scan")
    fun scan(@RequestHeader("Authorization", required = false) auth: String?): Map<String, Any> {
        requireAdmin(auth)
        val processed = service.runScan()
        return mapOf("success" to true, "data" to mapOf("processed" to processed))
    }

    /** dedup 테이블 현황 — 운영자 전용(운영 지표 노출 방지). */
    @GetMapping("/cleanup/stats")
    fun cleanupStats(@RequestHeader("Authorization", required = false) auth: String?): Map<String, Any> {
        requireAdmin(auth)
        return mapOf("success" to true, "data" to cleanupService.stats())
    }

    /** 운영용 수동 정리 — 운영자 전용(익명 DELETE 방지). days(기본 60) 보다 오래된 dedup row 삭제. */
    @PostMapping("/cleanup")
    fun cleanup(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(required = false, defaultValue = "60") days: Int,
    ): Map<String, Any> {
        requireAdmin(auth)
        val deleted = cleanupService.cleanup(days.coerceIn(1, 3650))
        return mapOf("success" to true, "data" to deleted)
    }
}
