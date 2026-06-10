package com.giwon.signaldesk.features.push.application

import org.springframework.stereotype.Service
import kotlin.math.abs

/**
 * 관심종목 감시. 순수 함수 — IO/DB/외부호출 없음.
 *
 * 발송 기준:
 *   - 변화율 ±5% 이상 (UP / DOWN) — 단, 최근 3일 마지막 알림 강도 + 5%p 이상일 때만 재알림 (스팸 방지)
 *   - 현재가 ≤ alertBelow (PRICE_BELOW) — 매수 타이밍 알림 (발송 후 service 가 자동 해제)
 *   - 현재가 ≥ alertAbove (PRICE_ABOVE) — 상한 도달 알림 (발송 후 service 가 자동 해제)
 *   - volumeRatio ≥ 2.0 AND volumeAlert=true (VOLUME_SPIKE) — 하루 1회
 *   - 같은 유저+종목+방향+날짜 조합은 하루 1회만 (dedup)
 *
 * 급등/급락 단계 재알림 (2026-05-29): 한번 +5% 알림 후 매일 반복 스팸 → 마지막 알림 강도보다
 * RATE_STEP_PCT(5%p) 더 변해야 재발송. 예: +5% 알림 → +6% 무시 → +11% 재알림.
 */
@Service
class WatchlistAlertDetector {

    private val changeRateThresholdPct = 5.0
    private val volumeSpikeThreshold = 2.0
    private val rateStepPct = 5.0  // 재알림 최소 추가 변동폭 (%p)

    data class WatchRow(
        val userId: java.util.UUID,
        val market: String,
        val ticker: String,
        val name: String,
        val changeRate: Double,
        val currentPrice: Int = 0,
        val alertBelow: Int? = null,
        val alertAbove: Int? = null,
        val volumeAlert: Boolean = false,
        val volumeRatio: Double? = null,
    )

    fun detect(
        rows: List<WatchRow>,
        alreadySent: Set<AlertLogEntry>,
        today: java.time.LocalDate,
        recentMaxRate: Map<AlertRateKey, Double> = emptyMap(),
    ): List<AlertCandidate> {
        return rows.flatMap { r -> detectForRow(r, alreadySent, today, recentMaxRate) }
    }

    private fun detectForRow(
        r: WatchRow,
        alreadySent: Set<AlertLogEntry>,
        today: java.time.LocalDate,
        recentMaxRate: Map<AlertRateKey, Double>,
    ): List<AlertCandidate> {
        val candidates = mutableListOf<AlertCandidate>()

        // ±5% 급등락 — 단계적 재알림: 마지막 알림 강도 + 5%p 이상일 때만.
        if (abs(r.changeRate) >= changeRateThresholdPct) {
            val direction = if (r.changeRate >= 0) AlertDirection.UP else AlertDirection.DOWN
            val notSentToday = AlertLogEntry(r.userId, r.ticker, direction, today) !in alreadySent
            val lastRate = recentMaxRate[AlertRateKey(r.userId, r.ticker, direction)]
            // 최근 알림 이력 없으면 첫 발송, 있으면 그 강도 + step 이상이어야 재발송.
            val strongEnough = lastRate == null || abs(r.changeRate) >= lastRate + rateStepPct
            if (notSentToday && strongEnough) {
                candidates += AlertCandidate(r.userId, r.ticker, r.name, r.market, r.changeRate, direction)
            }
        }

        // 하한 알림: 현재가가 alertBelow 이하로 떨어짐
        if (r.alertBelow != null && r.currentPrice > 0 && r.currentPrice <= r.alertBelow) {
            if (AlertLogEntry(r.userId, r.ticker, AlertDirection.PRICE_BELOW, today) !in alreadySent) {
                candidates += AlertCandidate(r.userId, r.ticker, r.name, r.market, r.changeRate, AlertDirection.PRICE_BELOW, r.currentPrice, r.alertBelow)
            }
        }

        // 상한 알림: 현재가가 alertAbove 이상으로 오름
        if (r.alertAbove != null && r.currentPrice > 0 && r.currentPrice >= r.alertAbove) {
            if (AlertLogEntry(r.userId, r.ticker, AlertDirection.PRICE_ABOVE, today) !in alreadySent) {
                candidates += AlertCandidate(r.userId, r.ticker, r.name, r.market, r.changeRate, AlertDirection.PRICE_ABOVE, r.currentPrice, r.alertAbove)
            }
        }

        // 거래량 급증
        if (r.volumeAlert && (r.volumeRatio ?: 0.0) >= volumeSpikeThreshold) {
            if (AlertLogEntry(r.userId, r.ticker, AlertDirection.VOLUME_SPIKE, today) !in alreadySent) {
                candidates += AlertCandidate(r.userId, r.ticker, r.name, r.market, r.changeRate, AlertDirection.VOLUME_SPIKE, volumeRatio = r.volumeRatio)
            }
        }

        return candidates
    }
}
