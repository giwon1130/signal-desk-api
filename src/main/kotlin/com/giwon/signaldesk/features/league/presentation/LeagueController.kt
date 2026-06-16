package com.giwon.signaldesk.features.league.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.league.application.LeagueService
import com.giwon.signaldesk.features.market.presentation.ApiResponse
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
import java.time.Instant
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/league")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class LeagueController(
    private val service: LeagueService,
    private val authContext: AuthContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun requireUserId(auth: String?): UUID =
        authContext.optionalUserId(auth) ?: throw com.giwon.signaldesk.features.auth.application.AuthException("로그인이 필요해요.")

    /** 새 league 생성 (DRAFT). 호스트는 자동 첫 참가자. */
    @PostMapping
    fun create(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody req: CreateLeagueRequest,
    ): ApiResponse<LeagueResponse> {
        val userId = requireUserId(auth)
        val league = service.create(
            hostUserId = userId,
            name = req.name,
            marketScope = req.marketScope,
            currency = req.currency,
            startingCapital = req.startingCapital,
            startedAt = Instant.parse(req.startedAt),
            endsAt = Instant.parse(req.endsAt),
            tradingHours = req.tradingHours,
            visibility = req.visibility,
            hostNickname = req.hostNickname,
            hostAvatarEmoji = req.hostAvatarEmoji,
        )
        log.info("league create — host={} id={}", userId, league.id)
        return ApiResponse(true, LeagueResponse.from(league))
    }

    /** 내 참여 league 목록 (호스트 + 게스트 모두). */
    @GetMapping("/my")
    fun listMy(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ApiResponse<List<LeagueResponse>> {
        val userId = requireUserId(auth)
        val list = service.listMy(userId).map(LeagueResponse::from)
        return ApiResponse(true, list)
    }

    /** 상세 — league + 참가자 (리더보드용). */
    @GetMapping("/{id}")
    fun detail(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<LeagueDetailResponse?> {
        // 인증은 했지만 본인 참가 여부 체크는 안 함 (코드 공유로 살펴보는 사용 케이스 허용).
        requireUserId(auth)
        val league = service.get(UUID.fromString(id))
            ?: return ApiResponse(true, null)
        val parts = service.listParticipants(league.id).map(ParticipantResponse::from)
        return ApiResponse(true, LeagueDetailResponse(LeagueResponse.from(league), parts))
    }

    /** DRAFT → OPEN — 호스트만. */
    @PostMapping("/{id}/open")
    fun open(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<LeagueResponse> {
        val userId = requireUserId(auth)
        val league = service.openForJoining(UUID.fromString(id), userId)
        return ApiResponse(true, LeagueResponse.from(league))
    }

    /** joinCode 로 참가. */
    @PostMapping("/join")
    fun join(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody req: JoinLeagueRequest,
    ): ApiResponse<LeagueDetailResponse> {
        val userId = requireUserId(auth)
        val (league, _) = service.joinByCode(req.joinCode, userId, req.nickname, req.avatarEmoji)
        val parts = service.listParticipants(league.id).map(ParticipantResponse::from)
        log.info("league join — code={} user={} leagueId={}", req.joinCode, userId, league.id)
        return ApiResponse(true, LeagueDetailResponse(LeagueResponse.from(league), parts))
    }

    /** 참가 취소 (DRAFT/OPEN 만). */
    @DeleteMapping("/{id}/leave")
    fun leave(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: String,
    ): ApiResponse<Boolean> {
        val userId = requireUserId(auth)
        service.leave(UUID.fromString(id), userId)
        return ApiResponse(true, true)
    }
}
