package com.giwon.signaldesk.features.push.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.push.application.AlertHistoryItem
import com.giwon.signaldesk.features.push.application.PushDevice
import com.giwon.signaldesk.features.push.application.PushRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/push/devices")
class PushDeviceController(
    @Autowired(required = false) private val pushRepository: PushRepository? = null,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    private val logger = LoggerFactory.getLogger(PushDeviceController::class.java)

    @PostMapping
    fun register(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: RegisterDeviceRequest,
    ): ApiResponse<PushDevice?> {
        val repo = pushRepository ?: return ApiResponse(false, null)
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, null)
        val device = repo.upsertDevice(userId, request.platform, request.expoToken)
        // 토큰 전체는 안 찍고 prefix 만 (보안). user 도 첫 8자.
        logger.info("push device register user={} platform={} tokenPrefix={}",
            userId.toString().take(8), request.platform, request.expoToken.take(20))
        return ApiResponse(true, device)
    }

    @DeleteMapping("/{token}")
    fun unregister(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable token: String,
    ): ApiResponse<Boolean> {
        val repo = pushRepository ?: return ApiResponse(false, false)
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, false)
        repo.deleteDevice(userId, token)
        logger.info("push device unregister user={} tokenPrefix={}",
            userId.toString().take(8), token.take(20))
        return ApiResponse(true, true)
    }
}

@RestController
@RequestMapping("/api/v1/push/alerts")
class PushAlertHistoryController(
    @Autowired(required = false) private val pushRepository: PushRepository? = null,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    @GetMapping
    fun list(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestParam(required = false, defaultValue = "30") limit: Int,
    ): ApiResponse<List<AlertHistoryItem>> {
        val repo = pushRepository ?: return ApiResponse(false, emptyList())
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, emptyList())
        return ApiResponse(true, repo.listAlertHistory(userId, limit.coerceIn(1, 200)))
    }
}

data class RegisterDeviceRequest(
    @field:NotBlank val platform: String,
    @field:NotBlank val expoToken: String,
)
