package com.giwon.signaldesk.features.push.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.AlertPreferences
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/me/alert-preferences")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AlertPreferenceController(
    private val service: AlertPreferenceService,
    private val authContext: AuthContext,
    private val planService: com.giwon.signaldesk.features.plan.PlanService,
) {
    @GetMapping
    fun get(@RequestHeader("Authorization") auth: String): AlertPreferences =
        service.get(authContext.requireUserId(auth))

    @PutMapping
    fun update(
        @RequestHeader("Authorization") auth: String,
        @RequestBody body: AlertPreferences,
    ): AlertPreferences {
        val userId = authContext.requireUserId(auth)
        // 장중·미국장 브리프는 PRO 전용 — FREE 는 새로 켜는 것만 차단(이미 켜둠은 유지 = grandfather).
        if (!planService.isPro(userId)) {
            val current = service.get(userId)
            val turningOnMidday = body.middayBriefEnabled && !current.middayBriefEnabled
            val turningOnEvening = body.eveningBriefEnabled && !current.eveningBriefEnabled
            require(!turningOnMidday && !turningOnEvening) {
                "장중·미국장 마감 브리프는 PRO 플랜 전용이에요. PRO 로 업그레이드하면 켤 수 있어요. 💎"
            }
        }
        return service.update(userId, body)
    }
}
