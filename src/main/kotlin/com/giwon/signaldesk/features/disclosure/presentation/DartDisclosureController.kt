package com.giwon.signaldesk.features.disclosure.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
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
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {

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

    /** 운영용 수동 트리거. 인증 없는 단순 endpoint — 운영 환경은 외부에서 차단. */
    @PostMapping("/scan")
    fun scan(): Map<String, Any> {
        val processed = service.runScan()
        return mapOf("success" to true, "data" to mapOf("processed" to processed))
    }

    /** dedup 테이블 현황 — 총 건수 + 가장 오래된 날짜 + 보관기간 후보별 삭제 대상 미리보기. */
    @GetMapping("/cleanup/stats")
    fun cleanupStats(): Map<String, Any> =
        mapOf("success" to true, "data" to cleanupService.stats())

    /** 운영용 수동 정리. days(기본 60) 보다 오래된 dedup row 삭제. 삭제 건수 반환. */
    @PostMapping("/cleanup")
    fun cleanup(@RequestParam(required = false, defaultValue = "60") days: Int): Map<String, Any> {
        val deleted = cleanupService.cleanup(days.coerceIn(1, 3650))
        return mapOf("success" to true, "data" to deleted)
    }
}
