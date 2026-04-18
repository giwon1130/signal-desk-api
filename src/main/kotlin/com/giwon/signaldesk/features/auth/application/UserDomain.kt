package com.giwon.signaldesk.features.auth.application

import java.time.Instant
import java.util.UUID

data class SignalUser(
    val id: UUID,
    val email: String,
    val passwordHash: String?,   // OAuth 유저는 null
    val nickname: String,
    val googleId: String? = null,
    val kakaoId: String? = null,
    val createdAt: Instant,
)
