package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

@Service
class WatchAlertService {

    fun buildWatchAlerts(
        alternativeSignals: List<AlternativeSignal>,
        news: List<MarketNews>,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
        aiRecommendations: AIRecommendationSection,
    ): List<WatchAlert> {
        val portfolioByKey = portfolio.positions.associateBy { "${it.market}:${it.ticker}" }
        val aiPickByKey = aiRecommendations.picks.associateBy { "${it.market}:${it.ticker}" }
        val hotSignals = alternativeSignals.filter { it.score >= 75 }.map { it.label }

        val alerts = watchlist.flatMap { item ->
            val key = "${item.market}:${item.ticker}"
            val relatedNews = news.filter { isRelatedNews(item, it) }
            val portfolioPosition = portfolioByKey[key]
            val aiPick = aiPickByKey[key]
            val itemAlerts = mutableListOf<WatchAlert>()

            val absoluteMove = kotlin.math.abs(item.changeRate)
            if (absoluteMove >= 2.5) {
                val isPositive = item.changeRate >= 0
                itemAlerts += WatchAlert(
                    severity = if (absoluteMove >= 4.5) "high" else "medium",
                    category = "price",
                    market = item.market, ticker = item.ticker, name = item.name,
                    title = if (isPositive) "가격 변동 확대" else "하락 변동성 확대",
                    note = "${item.name}이(가) ${formatSignedRate(item.changeRate)} 움직였어. 지금은 추격보다 뉴스 방향, 수급, 실험 지표가 같은 방향인지 먼저 보는 게 맞아.",
                    score = (absoluteMove * 15).toInt().coerceIn(48, 96),
                    tags = listOf("관심종목", if (isPositive) "급등" else "급락", item.market),
                )
            }

            if (relatedNews.size >= 2) {
                val impactScore = relatedNews.sumOf { estimateNewsImpact(it) }.coerceAtMost(100)
                itemAlerts += WatchAlert(
                    severity = if (impactScore >= 75) "high" else "medium",
                    category = "news",
                    market = item.market, ticker = item.ticker, name = item.name,
                    title = "관련 뉴스 집중",
                    note = "${item.name} 관련 뉴스가 ${relatedNews.size}건 묶였어. 가격 반응과 뉴스 방향이 엇갈리면 오히려 경계가 맞아.",
                    score = impactScore,
                    tags = listOf("관심종목", "뉴스 ${relatedNews.size}건", relatedNews.first().source),
                )
            }

            if (aiPick != null && aiPick.confidence >= 70 && aiPick.expectedReturnRate >= 4.0) {
                itemAlerts += WatchAlert(
                    severity = if (aiPick.confidence >= 80) "high" else "medium",
                    category = "ai",
                    market = item.market, ticker = item.ticker, name = item.name,
                    title = "AI 추천 논리 정렬",
                    note = "${item.name}은(는) AI 추천 신뢰도 ${aiPick.confidence}, 기대수익률 ${"%.1f".format(aiPick.expectedReturnRate)}%야. 추천 근거와 오늘 뉴스 흐름이 같은 방향인지 확인하면 돼.",
                    score = ((aiPick.confidence * 0.7) + (aiPick.expectedReturnRate * 4)).toInt().coerceIn(52, 97),
                    tags = listOf("AI 추천", aiPick.basis.take(14), item.market),
                )
            }

            if (portfolioPosition != null && kotlin.math.abs(portfolioPosition.profitRate) >= 5.0) {
                val favorable = portfolioPosition.profitRate >= 0
                itemAlerts += WatchAlert(
                    severity = when {
                        !favorable && kotlin.math.abs(portfolioPosition.profitRate) >= 8.0 -> "high"
                        !favorable -> "medium"
                        kotlin.math.abs(portfolioPosition.profitRate) >= 8.0 -> "medium"
                        else -> "low"
                    },
                    category = "portfolio",
                    market = item.market, ticker = item.ticker, name = item.name,
                    title = if (favorable) "보유 수익 구간 점검" else "보유 손실 구간 점검",
                    note = "${item.name} 보유 수익률이 ${formatSignedRate(portfolioPosition.profitRate)}야. 익절/손절 기준과 오늘 뉴스 흐름을 같이 확인해야 해.",
                    score = (kotlin.math.abs(portfolioPosition.profitRate) * 10).toInt().coerceIn(50, 94),
                    tags = listOf("보유종목", "${portfolioPosition.quantity}주", if (favorable) "익절체크" else "손실체크"),
                )
            }

            if (hotSignals.isNotEmpty() && item.market == "US" && (relatedNews.isNotEmpty() || aiPick != null)) {
                itemAlerts += WatchAlert(
                    severity = "medium", category = "signal",
                    market = item.market, ticker = item.ticker, name = item.name,
                    title = "실험 지표 과열 구간",
                    note = "${hotSignals.joinToString(", ")}가 같이 높아졌어. 미국 관심종목은 야간 뉴스와 정책 노이즈 영향을 크게 받을 수 있어.",
                    score = (60 + hotSignals.size * 8).coerceAtMost(90),
                    tags = listOf("실험지표", hotSignals.first(), item.market),
                )
            }

            itemAlerts
        }

        return alerts
            .sortedWith(
                compareByDescending<WatchAlert> { alertSeverityRank(it.severity) }
                    .thenByDescending { alertCategoryRank(it) }
                    .thenByDescending { it.score }
                    .thenBy { it.market }
                    .thenBy { it.ticker }
            )
            .distinctBy { "${it.market}:${it.ticker}" }
            .take(6)
    }

    fun buildBriefing(base: DailyBriefing, watchAlerts: List<WatchAlert>): DailyBriefing {
        val topAlerts = watchAlerts.take(3)
        val preMarketItems = topAlerts.take(2).map { alert ->
            when (alert.category) {
                "portfolio" -> "${alert.name}: 보유 ${alert.title} · ${alert.tags.joinToString(" / ")}"
                "ai" -> "${alert.name}: AI 추천 정렬 확인 · ${alert.tags.joinToString(" / ")}"
                else -> "${alert.name}: ${alert.title} · ${alert.tags.joinToString(" / ")}"
            }
        }
        val afterMarketItems = topAlerts.drop(1).take(2).map { alert ->
            "${alert.name}: 오늘 ${alertCategoryLabel(alert.category)} 신호가 실제 수익/손실과 연결됐는지 복기"
        }
        return base.copy(
            preMarket = (preMarketItems + base.preMarket).distinct().take(5),
            afterMarket = (afterMarketItems + base.afterMarket).distinct().take(5),
        )
    }

    private fun isRelatedNews(item: WatchItem, news: MarketNews): Boolean {
        val title = news.title.lowercase()
        return title.contains(item.name.lowercase()) || title.contains(item.ticker.lowercase())
    }

    private fun estimateNewsImpact(news: MarketNews): Int {
        val lowered = news.impact.lowercase()
        return when {
            lowered.contains("긴장") || lowered.contains("급등") || lowered.contains("쇼크") -> 42
            lowered.contains("강세") || lowered.contains("호재") || lowered.contains("수혜") -> 34
            lowered.contains("경계") || lowered.contains("관망") || lowered.contains("약세") -> 28
            else -> 22
        }
    }

    private fun alertSeverityRank(severity: String) = when (severity) { "high" -> 3; "medium" -> 2; else -> 1 }

    private fun alertCategoryRank(alert: WatchAlert) = when {
        alert.category == "portfolio" && alert.title.contains("손실") -> 6
        alert.category == "news" -> 5
        alert.category == "price" -> 4
        alert.category == "ai" -> 3
        alert.category == "signal" -> 2
        alert.category == "portfolio" -> 1
        else -> 0
    }

    private fun alertCategoryLabel(category: String) = when (category) {
        "portfolio" -> "보유"; "news" -> "뉴스"; "price" -> "가격"; "ai" -> "AI"; "signal" -> "실험"; else -> category
    }

    fun formatSignedRate(value: Double): String = if (value > 0) "+${"%.2f".format(value)}%" else "${"%.2f".format(value)}%"
}
