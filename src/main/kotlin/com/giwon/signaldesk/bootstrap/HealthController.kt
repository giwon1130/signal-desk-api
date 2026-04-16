package com.giwon.signaldesk.bootstrap

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(
    val status: String,
    val application: String,
    val storeMode: String,
)

@RestController
class HealthController {
    @GetMapping("/health")
    fun health(): HealthResponse {
        return HealthResponse(
            status = "UP",
            application = "signal-desk-api",
            storeMode = "ready",
        )
    }
}
