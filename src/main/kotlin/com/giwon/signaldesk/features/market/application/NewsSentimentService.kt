package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.Instant
import java.time.Clock
import java.time.Duration
import kotlin.math.roundToInt

/**
 * 최근 24시간 뉴스 제목의 표현만 분류한다. 투자심리나 실제 시장 방향을 추정하지 않는다.
 * - 각 뉴스 → +1 (긍정), -1 (부정), 0 (중립). 규칙 기반 [NewsToneClassifier].
 * - 종합 점수: 50 + (positive - negative) / tonedCount * 40 (0~100 clamp)
 * - 라벨: >= 60 긍정, <= 40 부정, 그 외 중립
 */
@Service
class NewsSentimentService(
    private val toneClassifier: NewsToneClassifier,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 시장별 노출 하이라이트 최대 개수. 소스는 시장당 30~50건을 받으므로 표본은 충분. */
    private val maxHighlights = 15

    fun build(market: String, news: List<MarketNews>): NewsSentiment {
        // Rolling UTC window also retains US session news across Korean midnight.
        val now = clock.instant()
        val filtered = news
            .filter { it.market.equals(market, ignoreCase = true) }
            .filter { item -> runCatching { Instant.parse(item.publishedAt).let { it <= now && it >= now.minus(Duration.ofHours(24)) } }.getOrDefault(false) }
            .filter { it.source.isNotBlank() && it.url.startsWith("https://") }
            .distinctBy { it.url.substringBefore('?') }
            .distinctBy { it.title.substringBeforeLast(" - ").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "") }
        val toneByTitle = toneClassifier.classify(market, filtered.map { it.title })
        val classified = filtered.map { it to (toneByTitle[it.title] ?: 0) }
        val pos = classified.count { it.second == 1 }
        val neg = classified.count { it.second == -1 }
        val rawTotal = classified.size                      // 실제 필터링된 뉴스 수 (0 가능)
        // score 분모는 '톤 있는'(긍정+부정) 기사만 사용한다. 중립 기사까지 분모에 넣으면(이전 구현)
        // 중립 헤드라인 수십 건이 신호를 희석해, 부정이 2배여도 라벨이 '중립'에 갇혔다.
        val toned = (pos + neg).coerceAtLeast(1)            // 0 divide 가드 (전부 중립이면 50 = 중립)
        val score = (50 + (pos - neg).toDouble() / toned * 40).coerceIn(0.0, 100.0).roundToInt()
        val label = when {
            rawTotal == 0 -> "자료 없음"
            score >= 60 -> "긍정"
            score <= 40 -> "부정"
            else        -> "중립"
        }
        // 하이라이트는 tone 이 긍정/부정인 기사를 먼저 올리고, 모자라면 중립으로 채운다.
        val tonedFirst = classified.sortedByDescending { (_, tone) -> if (tone != 0) 1 else 0 }
        val highlights = tonedFirst.take(maxHighlights).map { (n, tone) ->
            NewsHighlight(
                title = n.title,
                source = n.source,
                url = n.url,
                tone = when (tone) { 1 -> "긍정"; -1 -> "부정"; else -> "중립" },
                publishedAt = n.publishedAt,
            )
        }
        val rationale = when {
            rawTotal == 0        -> "최근 24시간 내 발행 시각과 출처가 확인된 뉴스가 없습니다. 시장이 안정적이라는 뜻은 아닙니다."
            pos > neg && pos > 0 -> "긍정 표현 ${pos}건, 부정 표현 ${neg}건입니다. 기사 제목의 표현 분류이며 실제 지수 방향이나 전망과는 다릅니다."
            neg > pos && neg > 0 -> "부정 표현 ${neg}건, 긍정 표현 ${pos}건입니다. 기사 제목의 표현 분류이며 하락 원인이나 매매 판단을 뜻하지 않습니다."
            else                 -> "기사 제목에서 한쪽 표현이 우세하지 않습니다. 시장이 보합이거나 혼조라는 뜻은 아닙니다."
        }
        return NewsSentiment(
            market = market,
            score = score,
            label = label,
            rationale = rationale,
            positiveCount = pos,
            negativeCount = neg,
            neutralCount = rawTotal - pos - neg,
            highlights = highlights,
        )
    }

}
