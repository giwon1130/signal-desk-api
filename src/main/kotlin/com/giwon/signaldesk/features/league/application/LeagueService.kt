package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.domain.LeagueVisibility
import com.giwon.signaldesk.features.league.domain.MarketScope
import com.giwon.signaldesk.features.league.domain.Participant
import com.giwon.signaldesk.features.league.domain.TradingHours
import com.giwon.signaldesk.features.league.repository.LeagueRepository
import com.giwon.signaldesk.features.league.repository.ParticipantRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Trading League — 시즌 CRUD + 참가/이탈 + 상태 전환.
 *
 * 거래(Trade) 는 [TradeService] — 시세 lock 등 별도 로직. (Phase C)
 * 자동 정산은 [LeagueSchedulerService] — endsAt cron. (Phase E)
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class LeagueService(
    private val leagues: LeagueRepository,
    private val participants: ParticipantRepository,
    private val planService: com.giwon.signaldesk.features.plan.PlanService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 호스트가 새 league 생성 — DRAFT 로 시작, 호스트가 자동으로 첫 참가자. */
    fun create(
        hostUserId: UUID,
        name: String,
        marketScope: MarketScope,
        currency: LeagueCurrency,
        startingCapital: Long,
        startedAt: Instant,
        endsAt: Instant,
        tradingHours: TradingHours = TradingHours.MARKET_HOURS_ONLY,
        visibility: LeagueVisibility = LeagueVisibility.OPEN,
        hostNickname: String,
        hostAvatarEmoji: String,
    ): League {
        require(name.isNotBlank()) { "name required" }
        require(startingCapital > 0) { "startingCapital must be > 0" }
        require(endsAt.isAfter(startedAt)) { "endsAt must be after startedAt" }
        // 시즌 너무 짧으면 게임 의미 X — 최소 1시간.
        require(endsAt.toEpochMilli() - startedAt.toEpochMilli() >= 3600_000) {
            "league duration must be >= 1 hour"
        }
        // FREE 상한 — 진행 중(FINISHED 아님) 리그만 카운트. 끝난 리그는 제외.
        val ongoing = leagues.findByHost(hostUserId).count { it.status != LeagueStatus.FINISHED }
        planService.assertCanAdd(hostUserId, com.giwon.signaldesk.features.plan.PlanService.Resource.LEAGUES, ongoing)
        val league = League(
            id = UUID.randomUUID(),
            name = name.trim(),
            hostUserId = hostUserId,
            joinCode = generateJoinCode(),
            marketScope = marketScope,
            currency = currency,
            startingCapital = startingCapital,
            startedAt = startedAt,
            endsAt = endsAt,
            status = LeagueStatus.DRAFT,
            tradingHours = tradingHours,
            fee = BigDecimal("0.003"),
            maxPositionPct = BigDecimal("0.30"),
            visibility = visibility,
            createdAt = Instant.now(),
        )
        leagues.create(league)

        // 호스트는 자동 첫 참가자.
        participants.add(Participant(
            leagueId = league.id, userId = hostUserId,
            joinedAt = Instant.now(),
            nickname = hostNickname.ifBlank { "호스트" },
            avatarEmoji = hostAvatarEmoji.ifBlank { "🐱" },
            cashBalance = startingCapital,
            finalReturnRate = null, finalRank = null,
        ))
        log.info("league created — id={} host={} code={}", league.id, hostUserId, league.joinCode)
        return league
    }

    /**
     * DRAFT → OPEN (모집) 또는 → RUNNING (즉시 시작).
     * startedAt 이 이미 지났으면 cron 1분 대기 없이 즉시 RUNNING — UX 개선.
     */
    fun openForJoining(leagueId: UUID, requesterUserId: UUID): League {
        val l = leagues.findById(leagueId) ?: error("league not found")
        require(l.hostUserId == requesterUserId) { "only host can open league" }
        require(l.status == LeagueStatus.DRAFT) { "league must be DRAFT (was ${l.status})" }
        // 시작 시각이 이미 지났으면 OPEN 건너뛰고 바로 RUNNING — '지금부터' 만든 league 즉시 매수 가능.
        val newStatus = if (!l.startedAt.isAfter(Instant.now())) LeagueStatus.RUNNING else LeagueStatus.OPEN
        leagues.updateStatus(leagueId, newStatus)
        log.info("league opened — id={} status={}", leagueId, newStatus)
        return l.copy(status = newStatus)
    }

    /** joinCode 로 참가. */
    fun joinByCode(
        joinCode: String,
        userId: UUID,
        nickname: String,
        avatarEmoji: String,
    ): Pair<League, Participant> {
        val l = leagues.findByJoinCode(joinCode.trim().uppercase()) ?: error("join code not found")
        // FINISHED 만 거부 — 호스트가 만든 즉시 RUNNING 으로 가는 흐름(openForJoining)이라
        // RUNNING 도중 친구 참가도 허용해야 한다.
        require(l.status != LeagueStatus.FINISHED) {
            "league already finished"
        }
        // 이미 참가했으면 그대로 반환 (idempotent).
        participants.find(l.id, userId)?.let { return l to it }
        // 인원 제한 10명.
        require(participants.count(l.id) < MAX_PARTICIPANTS) {
            "league full (max=$MAX_PARTICIPANTS)"
        }
        val p = try {
            participants.add(Participant(
                leagueId = l.id, userId = userId,
                joinedAt = Instant.now(),
                nickname = nickname.ifBlank { "참가자" },
                avatarEmoji = avatarEmoji.ifBlank { "🐱" },
                cashBalance = l.startingCapital,
                finalReturnRate = null, finalRank = null,
            ))
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // 동시 더블탭 등으로 (league,user) 유니크 충돌 — 이미 참가한 것으로 보고 멱등 반환.
            participants.find(l.id, userId)?.let { return l to it }
            throw e
        }
        log.info("league joined — id={} user={}", l.id, userId)
        return l to p
    }

    /** 참가 취소 — DRAFT/OPEN 만 허용 (RUNNING 시작되면 X). */
    fun leave(leagueId: UUID, userId: UUID) {
        val l = leagues.findById(leagueId) ?: error("league not found")
        require(l.status == LeagueStatus.DRAFT || l.status == LeagueStatus.OPEN) {
            "cannot leave after league started"
        }
        require(l.hostUserId != userId) { "host cannot leave own league" }
        participants.delete(leagueId, userId)
        log.info("league left — id={} user={}", leagueId, userId)
    }

    fun get(id: UUID): League? = leagues.findById(id)
    fun listMy(userId: UUID): List<League> = leagues.findByParticipant(userId)
    fun listParticipants(leagueId: UUID): List<Participant> = participants.findByLeague(leagueId)

    /** 5자리 alphanumeric (대문자+숫자) — 중복 시 재시도. */
    private fun generateJoinCode(): String {
        val chars = "ABCDEFGHIJKLMNPQRSTUVWXYZ23456789"  // 0/O, 1/I 제외 (혼동 방지)
        repeat(10) {
            val code = (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
            if (leagues.findByJoinCode(code) == null) return code
        }
        // 천에 한 번도 안 될 확률 — 그래도 보호용.
        error("join code generation failed (try again)")
    }

    companion object {
        const val MAX_PARTICIPANTS = 10
    }
}
