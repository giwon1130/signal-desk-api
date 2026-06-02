package com.giwon.signaldesk.features.reading.presentation

import com.giwon.signaldesk.features.reading.application.StockMentionDetector
import com.giwon.signaldesk.features.reading.domain.CallPerformance
import com.giwon.signaldesk.features.reading.domain.Leader
import com.giwon.signaldesk.features.reading.domain.ReadingCall
import com.giwon.signaldesk.features.reading.domain.ReadingPost
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

// ─── 요청 ────────────────────────────────────────────────────────────────────
data class ApplyLeaderRequest(
    @field:NotBlank val displayName: String,
    val bio: String = "",
)

data class SubscribeRequest(
    @field:NotBlank val inviteCode: String,
)

/** 리더 자격 — 권한 있는 계정만 '리더 되기' 노출. */
data class EligibilityResponse(
    val canLead: Boolean,
)

data class DetectRequest(
    @field:NotBlank val body: String,
)

data class PublishPostRequest(
    @field:NotBlank val title: String,
    val body: String = "",
    val visibility: String = "FOLLOWERS",   // FOLLOWERS | PUBLIC
    val calls: List<CallInputDto> = emptyList(),
)

data class CallInputDto(
    @field:NotBlank val market: String,      // KR | US
    @field:NotBlank val ticker: String,
    val name: String = "",
    val targetReturnPct: BigDecimal? = null,
)

// ─── 응답 ────────────────────────────────────────────────────────────────────
data class LeaderResponse(
    val userId: String,
    val displayName: String,
    val bio: String,
    val inviteCode: String?,     // 본인/구독자 화면에서만 노출
    val status: String,
    val followerCount: Int,
) {
    companion object {
        fun from(l: Leader, followerCount: Int, includeCode: Boolean) = LeaderResponse(
            userId = l.userId.toString(),
            displayName = l.displayName,
            bio = l.bio,
            inviteCode = if (includeCode) l.inviteCode else null,
            status = l.status.name,
            followerCount = followerCount,
        )
    }
}

data class MentionDto(
    val ticker: String,
    val name: String,
    val market: String,
    val matchedText: String,
    val confidence: String,
) {
    companion object {
        fun from(m: StockMentionDetector.DetectedMention) =
            MentionDto(m.ticker, m.name, m.market, m.matchedText, m.confidence)
    }
}

data class CallResponse(
    val id: String,
    val market: String,
    val ticker: String,
    val name: String,
    val entryPrice: BigDecimal,
    val entryCurrency: String,
    val targetReturnPct: BigDecimal?,
    val status: String,
    val currentPrice: BigDecimal?,
    val returnPct: Double?,
    val entryLockedAt: String,
) {
    companion object {
        fun from(c: ReadingCall, currentPrice: BigDecimal?, returnPct: Double?) = CallResponse(
            id = c.id.toString(),
            market = c.market, ticker = c.ticker, name = c.name,
            entryPrice = c.entryPrice, entryCurrency = c.entryCurrency.name,
            targetReturnPct = c.targetReturnPct, status = c.status.name,
            currentPrice = currentPrice, returnPct = returnPct,
            entryLockedAt = c.entryLockedAt.toString(),
        )
        fun from(p: CallPerformance) = from(p.call, p.currentPrice, p.returnPct)
    }
}

data class PostResponse(
    val id: String,
    val leaderUserId: String,
    val leaderName: String,
    val title: String,
    val body: String,
    val visibility: String,
    val createdAt: String,
    val calls: List<CallResponse>,
) {
    companion object {
        fun from(post: ReadingPost, leaderName: String, calls: List<CallResponse>) = PostResponse(
            id = post.id.toString(),
            leaderUserId = post.leaderUserId.toString(),
            leaderName = leaderName,
            title = post.title,
            body = post.body,
            visibility = post.visibility.name,
            createdAt = post.createdAt.toString(),
            calls = calls,
        )
    }
}
