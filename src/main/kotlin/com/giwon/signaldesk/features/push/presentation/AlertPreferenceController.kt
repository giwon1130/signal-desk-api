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
) {
    @GetMapping
    fun get(@RequestHeader("Authorization") auth: String): AlertPreferences =
        service.get(authContext.requireUserId(auth))

    @PutMapping
    fun update(
        @RequestHeader("Authorization") auth: String,
        @RequestBody body: AlertPreferences,
    ): AlertPreferences =
        service.update(authContext.requireUserId(auth), body)
}
