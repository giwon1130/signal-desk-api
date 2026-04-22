package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * AI 추천 track record로 "지난 N일 적중률 / 평균 수익률"을 계산한다.
 * 빈 트랙 레코드면 null 을 돌려 UI 에서 카드 자체를 숨길 수 있도록.
 */
@Service
class RecommendationMetricsCalculator(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun compute(
        trackRecords: List<RecommendationTrackRecord>,
        windowDays: Int = 30,
    ): RecommendationMetrics? {
        if (trackRecords.isEmpty()) return null
        val cutoff = LocalDate.now(clock).minusDays(windowDays.toLong())
        val window = trackRecords.filter {
            runCatching { LocalDate.parse(it.recommendedDate) }
                .getOrNull()
                ?.isAfter(cutoff.minusDays(1)) == true
        }
        if (window.isEmpty()) return null

        val total = window.size
        val successCount = window.count { it.success }
        val hitRate = successCount.toDouble() / total
        val returns = window.map { it.realizedReturnRate }
        return RecommendationMetrics(
            windowDays = windowDays,
            totalCount = total,
            successCount = successCount,
            hitRate = hitRate,
            averageReturnRate = returns.average(),
            bestReturnRate = returns.max(),
            worstReturnRate = returns.min(),
        )
    }
}
