package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.repository.LeagueRepository
import com.giwon.signaldesk.features.league.repository.ParticipantRepository
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * League 자동 상태 전환:
 *  - OPEN + startedAt 도달 → RUNNING (auto-start)
 *  - RUNNING + endsAt 도달 → 정산 + FINISHED (auto-finish)
 *
 * 매 분 폴링 — 정확도 ±60초 (게임이라 OK).
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class LeagueSchedulerService(
    private val leagues: LeagueRepository,
    private val participants: ParticipantRepository,
    private val leaderboard: LeaderboardService,
    private val pushRepo: PushRepository,
    private val expoPush: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 매 분 0초. */
    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    fun tick() {
        val now = Instant.now()
        runCatching { startReady(now) }.onFailure { log.error("auto-start failed", it) }
        runCatching { finishExpired(now) }.onFailure { log.error("auto-finish failed", it) }
    }

    private fun startReady(now: Instant) {
        val ready = leagues.findOpenReadyToStart(now)
        if (ready.isEmpty()) return
        ready.forEach { l ->
            leagues.updateStatus(l.id, LeagueStatus.RUNNING)
            log.info("league auto-started — id={} name={}", l.id, l.name)
            runCatching { notifyStart(l) }.onFailure { log.warn("start push failed — id={}", l.id, it) }
        }
    }

    private fun finishExpired(now: Instant) {
        val expired = leagues.findRunningReadyToFinish(now)
        if (expired.isEmpty()) return
        expired.forEach { l ->
            runCatching {
                leaderboard.finalize(l.id)
                notifyFinish(l)
            }.onFailure { log.error("finalize failed — id={}", l.id, it) }
        }
    }

    /** 시작 알림 — 모든 참가자에게. */
    private fun notifyStart(league: League) {
        val tokens = participantTokens(league)
        if (tokens.isEmpty()) return
        val msgs = tokens.map { token ->
            ExpoPushClient.Message(
                to = token,
                title = "🏁 ${league.name} 시작!",
                body = "지금부터 거래하세요 — 종료 시점 수익률 1등이 우승",
                data = mapOf("type" to "LEAGUE_STARTED", "leagueId" to league.id.toString()),
            )
        }
        expoPush.send(msgs)
        log.info("league start push — id={} sent={}", league.id, msgs.size)
    }

    /** 종료 알림 — 우승자 이름 + 본인 등수 포함하면 더 좋지만 단순화: 우승자만 노출. */
    private fun notifyFinish(league: League) {
        val tokens = participantTokens(league)
        if (tokens.isEmpty()) return
        // 우승자 찾기 (final_rank=1)
        val all = participants.findByLeague(league.id)
        val winner = all.firstOrNull { it.finalRank == 1 }
        val winnerLabel = winner?.let {
            "🏆 ${it.nickname} +${"%.2f".format((it.finalReturnRate?.toDouble() ?: 0.0) * 100)}%"
        } ?: "🏆 정산 완료"
        val msgs = tokens.map { token ->
            ExpoPushClient.Message(
                to = token,
                title = "${league.name} 종료",
                body = "$winnerLabel — 결과 확인하기",
                data = mapOf("type" to "LEAGUE_FINISHED", "leagueId" to league.id.toString()),
            )
        }
        expoPush.send(msgs)
        log.info("league finish push — id={} sent={} winner={}", league.id, msgs.size, winner?.nickname)
    }

    private fun participantTokens(league: League): List<String> {
        val participantUserIds = participants.findByLeague(league.id).map { it.userId }.toSet()
        if (participantUserIds.isEmpty()) return emptyList()
        val devicesByUser = pushRepo.listAllDevicesGroupedByUser()
        return participantUserIds.flatMap { uid ->
            devicesByUser[uid].orEmpty().map { it.expoToken }
        }
    }
}
