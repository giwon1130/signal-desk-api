package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import com.giwon.signaldesk.features.reading.domain.CallStatus
import com.giwon.signaldesk.features.reading.domain.ReadingCall
import com.giwon.signaldesk.features.reading.repository.ReadingRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * "거봐 내가 말했지?" 알림 (Phase E).
 *
 * ACTIVE 콜 중 목표 도달 콜을 찾아 HIT 마킹 + 리더/팔로워에게 푸시.
 * 목표 도달 = 콜 등록 시점 가격(박제) 대비 현재가가 targetReturnPct 에 도달.
 *  - target >= 0 (상승 콜): returnPct >= target 일 때
 *  - target <  0 (하락 콜): returnPct <= target 일 때 (양방향 정직, §12)
 *  - target null: 기본 +15% 상승 기준 (§12)
 *
 * 재알림 방지: HIT 마킹 시 ACTIVE 에서 빠지므로 자연 dedup. (목표가 1회 도달 후 자동 종료, §12)
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class ReadingCallAlertService(
    private val repo: ReadingRepository,
    private val priceService: ReadingPriceService,
    private val pushRepository: PushRepository,
    private val expoPush: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 시장 구분용 — KR 장중 / US 장중에 각각 호출. null 이면 전체. */
    fun scanAndNotify(marketFilter: String? = null) {
        val active = repo.activeCalls().filter { marketFilter == null || it.market == marketFilter }
        if (active.isEmpty()) return

        val priceMap = priceService.currentPrices(active.map { it.market to it.ticker })
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        val now = Instant.now()
        val expiryBefore = now.minus(EXPIRY_DAYS, ChronoUnit.DAYS)
        var hits = 0
        var closed = 0

        for (call in active) {
            val current = priceMap[call.market to call.ticker] ?: continue
            val ret = current.subtract(call.entryPrice)
                .divide(call.entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).toDouble()
            val target = call.targetReturnPct?.toDouble() ?: DEFAULT_TARGET_PCT
            val reached = if (target >= 0) ret >= target else ret <= target
            if (reached) {
                // 결착가(current) 박제 — 이후 시세 변동에도 적중/수익률 고정.
                repo.markCallStatus(call.id, CallStatus.HIT, now, current)
                hits++
                runCatching { notifyHit(call, ret, devicesByUser) }
                    .onFailure { log.warn("reading hit notify failed call={}: {}", call.id, it.message) }
            } else if (call.entryLockedAt.isBefore(expiryBefore)) {
                // 장기 미도달 콜은 현재가로 결착(CLOSED) — 적중률 통계에 정직 반영(무기한 ACTIVE 방지).
                repo.markCallStatus(call.id, CallStatus.CLOSED, null, current)
                closed++
            }
        }
        if (hits > 0 || closed > 0)
            log.info("reading call alert — market={} active={} hits={} closed={}", marketFilter, active.size, hits, closed)
    }

    private fun notifyHit(
        call: ReadingCall,
        returnPct: Double,
        devicesByUser: Map<UUID, List<com.giwon.signaldesk.features.push.application.PushDevice>>,
    ) {
        val leader = repo.findLeader(call.leaderUserId)
        val leaderName = leader?.displayName ?: "리더"
        // 수신자 = 리더 본인 + 팔로워 (리더에게도 '거봐' 만족감을 줌)
        val recipients = (repo.followerIds(call.leaderUserId) + call.leaderUserId).distinct()
        val sign = if (returnPct >= 0) "+" else ""
        val pct = "%.1f".format(returnPct)
        val title = "📣 ${leaderName}님 콜 적중!"
        val body = "${call.name} $sign$pct% — \"거봐 내가 말했지?\""

        val messages = recipients.flatMap { uid ->
            (devicesByUser[uid] ?: emptyList()).map { dev ->
                ExpoPushClient.Message(
                    to = dev.expoToken,
                    title = title,
                    body = body,
                    data = mapOf(
                        "type" to "READING_CALL_HIT",
                        "callId" to call.id.toString(),
                        "leaderUserId" to call.leaderUserId.toString(),
                        "ticker" to call.ticker,
                        "market" to call.market,
                    ),
                    userId = uid, // 방해금지 게이트 적용 (US 콜 적중은 KST 새벽에 뜬다)
                )
            }
        }
        expoPush.send(messages)
    }

    companion object {
        const val DEFAULT_TARGET_PCT = 15.0
        const val EXPIRY_DAYS = 45L  // 결착 안 된 콜 자동 종료 기준(스윙 호흡 + 여유)
    }
}
