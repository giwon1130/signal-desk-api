package com.giwon.signaldesk.features.traderbridge

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class TraderBridgeController(
    @Autowired(required = false) private val service: TraderBridgeService? = null,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    @PostMapping("/api/v1/me/trader-connection")
    fun create(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<TraderConnectionCreated?> {
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, null)
        return ApiResponse(true, service?.createOrRotate(userId))
    }

    @GetMapping("/api/v1/me/trader-connection")
    fun status(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<TraderConnectionStatus?> {
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, null)
        return ApiResponse(true, service?.status(userId))
    }

    @DeleteMapping("/api/v1/me/trader-connection")
    fun disconnect(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<Boolean> {
        val userId = authContext?.requireUserId(auth) ?: return ApiResponse(false, false)
        service?.disconnect(userId)
        return ApiResponse(true, true)
    }

    /** 개인 trader 전용 업로드. 읽기 모델만 받으며 주문 실행 엔드포인트는 제공하지 않는다. */
    @PostMapping("/api/v1/trader-bridge/snapshot")
    fun publish(
        @RequestHeader("X-Signal-Desk-Trader-Key", required = false) connectionKey: String?,
        @RequestBody snapshot: TraderSnapshot,
    ): ApiResponse<TraderConnectionStatus?> =
        ApiResponse(true, service?.publish(connectionKey, snapshot))
}
