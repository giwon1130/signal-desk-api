package com.giwon.signaldesk.features.push.application

import org.springframework.stereotype.Service
import kotlin.math.abs

/**
 * 관심종목 감시. 순수 함수 — IO/DB/외부호출 없음.
 *
 * 발송 기준:
 *   - 당일 변화율 ±5% 이상
 *   - 같은 유저+종목+방향+날짜 조합은 하루 1회만 (dedup)
 */
@Service
class WatchlistAlertDetector {

    private val thresholdPct = 5.0

    data class WatchRow(
        val userId: java.util.UUID,
        val market: String,
        val ticker: String,
        val name: String,
        val changeRate: Double,
    )

    fun detect(rows: List<WatchRow>, alreadySent: Set<AlertLogEntry>, today: java.time.LocalDate): List<AlertCandidate> {
        return rows.mapNotNull { r ->
            if (abs(r.changeRate) < thresholdPct) return@mapNotNull null
            val direction = if (r.changeRate >= 0) AlertDirection.UP else AlertDirection.DOWN
            val logKey = AlertLogEntry(r.userId, r.ticker, direction, today)
            if (logKey in alreadySent) return@mapNotNull null
            AlertCandidate(
                userId = r.userId,
                ticker = r.ticker,
                name = r.name,
                market = r.market,
                changeRate = r.changeRate,
                direction = direction,
            )
        }
    }
}
