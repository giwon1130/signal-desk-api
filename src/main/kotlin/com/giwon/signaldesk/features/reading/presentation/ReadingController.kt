package com.giwon.signaldesk.features.reading.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.reading.application.ReadingService
import com.giwon.signaldesk.features.reading.application.StockMentionDetector
import com.giwon.signaldesk.features.reading.domain.PostVisibility
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 리딩(Leading Call) API — 리더 신청/승인, 구독, 종목 자동인식, 글 게시.
 * 피드/성과 조회는 [ReadingFeedController] (Phase D).
 */
@Validated
@RestController
@RequestMapping("/api/v1/reading")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class ReadingController(
    private val service: ReadingService,
    private val detector: StockMentionDetector,
    private val authContext: AuthContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun requireUserId(auth: String?): UUID =
        authContext.optionalUserId(auth) ?: error("auth required")

    // ─── 리더 ────────────────────────────────────────────────────────────────
    /** 리더 신청 (운영자는 즉시 승인). */
    @PostMapping("/leader/apply")
    fun applyLeader(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody req: ApplyLeaderRequest,
    ): ApiResponse<LeaderResponse> {
        val userId = requireUserId(auth)
        val leader = service.applyForLeader(userId, req.displayName, req.bio)
        return ApiResponse(true, LeaderResponse.from(leader, service.followerCount(userId), includeCode = true))
    }

    /** 리더 자격 — 권한 있는 계정만 '리더 되기' 노출(앱). */
    @GetMapping("/eligibility")
    fun eligibility(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<EligibilityResponse> {
        val userId = requireUserId(auth)
        return ApiResponse(true, EligibilityResponse(canLead = service.canLead(userId)))
    }

    /** 내 리더 프로필 (없으면 null). */
    @GetMapping("/leader/me")
    fun myLeader(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<LeaderResponse?> {
        val userId = requireUserId(auth)
        val leader = service.getLeader(userId) ?: return ApiResponse(true, null)
        return ApiResponse(true, LeaderResponse.from(leader, service.followerCount(userId), includeCode = true))
    }

    /** 운영자가 PENDING 리더 승인. */
    @PostMapping("/leader/{targetUserId}/approve")
    fun approve(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable targetUserId: String,
    ): ApiResponse<LeaderResponse> {
        val adminUserId = requireUserId(auth)
        val target = UUID.fromString(targetUserId)
        val leader = service.approveLeader(adminUserId, target)
        return ApiResponse(true, LeaderResponse.from(leader, service.followerCount(target), includeCode = false))
    }

    // ─── 구독 ────────────────────────────────────────────────────────────────
    @PostMapping("/subscribe")
    fun subscribe(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody req: SubscribeRequest,
    ): ApiResponse<LeaderResponse> {
        val userId = requireUserId(auth)
        val leader = service.subscribe(userId, req.inviteCode)
        return ApiResponse(true, LeaderResponse.from(leader, service.followerCount(leader.userId), includeCode = false))
    }

    @DeleteMapping("/subscribe/{leaderUserId}")
    fun unsubscribe(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable leaderUserId: String,
    ): ApiResponse<Boolean> {
        val userId = requireUserId(auth)
        service.unsubscribe(userId, UUID.fromString(leaderUserId))
        return ApiResponse(true, true)
    }

    /** 내가 구독 중인 리더 목록. */
    @GetMapping("/following")
    fun following(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<List<LeaderResponse>> {
        val userId = requireUserId(auth)
        val list = service.followingLeaders(userId)
            .map { LeaderResponse.from(it, service.followerCount(it.userId), includeCode = false) }
        return ApiResponse(true, list)
    }

    // ─── 종목 자동 인식 ────────────────────────────────────────────────────────
    /** 본문에서 종목 후보 검출 (작성 화면 보조 — 작성자가 확정). */
    @PostMapping("/detect")
    fun detect(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody req: DetectRequest,
    ): ApiResponse<List<MentionDto>> {
        requireUserId(auth)
        return ApiResponse(true, detector.detect(req.body).map(MentionDto::from))
    }

    // ─── 글 게시 ────────────────────────────────────────────────────────────────
    /** 리딩 글 게시 + 확정 콜 가격 박제. APPROVED 리더만. */
    @PostMapping("/posts")
    fun publish(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody req: PublishPostRequest,
    ): ApiResponse<PostResponse> {
        val userId = requireUserId(auth)
        val visibility = runCatching { PostVisibility.valueOf(req.visibility.uppercase()) }
            .getOrDefault(PostVisibility.FOLLOWERS)
        val calls = req.calls.map {
            ReadingService.CallInput(it.market, it.ticker, it.name, it.targetReturnPct)
        }
        val (post, created) = service.publishPost(userId, req.title, req.body, visibility, calls)
        val leaderName = service.getLeader(userId)?.displayName ?: "리더"
        // 갓 박제 — currentPrice=entryPrice, returnPct=0 으로 즉시 표시.
        val callResp = created.map { CallResponse.from(it, it.entryPrice, 0.0) }
        log.info("reading publish — user={} post={} calls={}", userId, post.id, created.size)
        return ApiResponse(true, PostResponse.from(post, leaderName, callResp))
    }
}
