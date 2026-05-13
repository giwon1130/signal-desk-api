package com.giwon.signaldesk.features.push.application

import org.springframework.stereotype.Service
import kotlin.math.abs

/**
 * 관심종목 감시. 순수 함수 — IO/DB/외부호출 없음.
 *
 * 발송 기준:
 *   - 당일 변화율 ±5% 이상 (UP / DOWN)
 *   - 현재가 ≤ alertBelow (PRICE_BELOW) — 매수 타이밍 알림
 *   - 현재가 ≥ alertAbove (PRICE_ABOVE) — 상한 도달 알림
 *   - volumeRatio ≥ 3.0 AND volumeAlert=true (VOLUME_SPIKE)
 *   - 같은 유저+종목+방향+날짜 조합은 하루 1회만 (dedup)
 */
@Service
class WatchlistAlertDetector {

    private val changeRateThresholdPct = 5.0
    private val volumeSpikeThreshold = 3.0

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

    fun detect(rows: List<WatchRow>, alreadySent: Set<AlertLogEntry>, today: java.time.LocalDate): List<AlertCandidate> {
        return rows.flatMap { r -> detectForRow(r, alreadySent, today) }
    }

    private fun detectForRow(r: WatchRow, alreadySent: Set<AlertLogEntry>, today: java.time.LocalDate): List<AlertCandidate> {
        val candidates = mutableListOf<AlertCandidate>()

        // ±5% 급등락
        if (abs(r.changeRate) >= changeRateThresholdPct) {
            val direction = if (r.changeRate >= 0) AlertDirection.UP else AlertDirection.DOWN
            if (AlertLogEntry(r.userId, r.ticker, direction, today) !in alreadySent) {
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
