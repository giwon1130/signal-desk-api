package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 뉴스 헤드라인의 톤을 모아 시장 sentiment 를 추정.
 * - 각 뉴스 → +1 (긍정), -1 (부정), 0 (중립). 톤 판정은 [NewsToneClassifier] (Gemini 1순위 + 키워드 폴백).
 * - 종합 점수: 50 + (positive - negative) / tonedCount * 40 (0~100 clamp)
 * - 라벨: >= 60 긍정, <= 40 부정, 그 외 중립
 */
@Service
class NewsSentimentService(
    private val toneClassifier: NewsToneClassifier,
) {
    /** 시장별 노출 하이라이트 최대 개수. 소스는 시장당 30~50건을 받으므로 표본은 충분. */
    private val maxHighlights = 15
    private val kst = ZoneId.of("Asia/Seoul")

    fun build(market: String, news: List<MarketNews>): NewsSentiment {
        // '오늘의 뉴스' — Google News RSS 는 관련도순이라 어제 기사가 섞여 들어온다.
        // KST 오늘 발행분만 남긴다. (입력을 거르므로 sentiment 점수·하이라이트가 모두 오늘 기준)
        val today = LocalDate.now(kst)
        val filtered = news
            .filter { it.market.equals(market, ignoreCase = true) }
            .filter { isPublishedOn(it.publishedAt, today) }
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
            rawTotal == 0        -> "분석 가능한 뉴스가 없어"
            pos > neg && pos > 0 -> "긍정 헤드라인 ${pos}건 vs 부정 ${neg}건 — 위험자산 선호 우세"
            neg > pos && neg > 0 -> "부정 헤드라인 ${neg}건 vs 긍정 ${pos}건 — 방어 모드 권장"
            else                 -> "긍정/부정 균형 — 종목별 차별화 장세 예상"
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

    /**
     * publishedAt(UTC instant, 예 "2026-06-10T01:24:00Z") 이 KST 기준 today 인가.
     * 날짜가 없거나(null) 파싱 실패하면 true — 날짜 모르는 항목을 숨겨 과필터하지 않는다.
     */
    private fun isPublishedOn(publishedAt: String?, today: LocalDate): Boolean {
        if (publishedAt.isNullOrBlank()) return true
        return runCatching {
            Instant.parse(publishedAt).atZone(kst).toLocalDate() == today
        }.getOrDefault(true)
    }
}
