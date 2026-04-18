package com.giwon.signaldesk.features.market.application

import kotlin.math.roundToInt

/**
 * 뉴스 헤드라인의 키워드를 보고 시장 sentiment 를 추정.
 * - 각 뉴스 → +1 (긍정), -1 (부정), 0 (중립)
 * - 종합 점수: 50 + (positive - negative) / count * 40 (0~100 clamp)
 * - 라벨: >= 60 긍정, <= 40 부정, 그 외 중립
 *
 * 진짜 NLP 가 들어오기 전 임시 휴리스틱. 데이터 소스가 풍부해지면 모델 호출로 교체.
 */
object NewsSentimentBuilder {

    private val positiveKeywords = listOf(
        "급등", "강세", "호재", "수혜", "최고치", "신고가", "반등", "상승", "회복", "낙관", "기대",
        "돌파", "역대급", "초강세", "랠리", "사상최고", "호조", "성장", "확대", "개선",
    )
    private val negativeKeywords = listOf(
        "급락", "약세", "악재", "쇼크", "최저치", "하락", "손실", "위기", "우려", "경고", "부진",
        "하향", "충격", "공포", "위축", "하한가", "패닉", "긴축", "감소", "악화",
    )

    fun build(market: String, news: List<MarketNews>): NewsSentiment {
        val filtered = news.filter { it.market.equals(market, ignoreCase = true) }
        val classified = filtered.map { it to classifyTone(it.title) }
        val pos = classified.count { it.second == 1 }
        val neg = classified.count { it.second == -1 }
        val total = classified.size.coerceAtLeast(1)
        val score = (50 + (pos - neg).toDouble() / total * 40).coerceIn(0.0, 100.0).roundToInt()
        val label = when {
            score >= 60 -> "긍정"
            score <= 40 -> "부정"
            else        -> "중립"
        }
        val highlights = classified.take(5).map { (n, tone) ->
            NewsHighlight(
                title = n.title,
                source = n.source,
                url = n.url,
                tone = when (tone) { 1 -> "긍정"; -1 -> "부정"; else -> "중립" },
            )
        }
        val rationale = when {
            pos > neg && pos > 0 -> "긍정 헤드라인 ${pos}건 vs 부정 ${neg}건 — 위험자산 선호 우세"
            neg > pos && neg > 0 -> "부정 헤드라인 ${neg}건 vs 긍정 ${pos}건 — 방어 모드 권장"
            total == 0           -> "분석 가능한 뉴스가 없어"
            else                 -> "긍정/부정 균형 — 종목별 차별화 장세 예상"
        }
        return NewsSentiment(
            market = market,
            score = score,
            label = label,
            rationale = rationale,
            positiveCount = pos,
            negativeCount = neg,
            neutralCount = total - pos - neg,
            highlights = highlights,
        )
    }

    private fun classifyTone(title: String): Int {
        val lower = title.lowercase()
        val pos = positiveKeywords.count { lower.contains(it) }
        val neg = negativeKeywords.count { lower.contains(it) }
        return when {
            pos > neg -> 1
            neg > pos -> -1
            else      -> 0
        }
    }
}
