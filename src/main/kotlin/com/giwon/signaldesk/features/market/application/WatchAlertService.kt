package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

/**
 * 관심종목 UI 카드 빌더 — `WatchAlert` 데이터 객체를 만들어 MarketOverview 응답에 실어 보낸다.
 *
 * **푸시 알림과 무관** — 푸시 실발송은 `features.push.application.WatchlistAlertService` 책임.
 * 본 클래스는 화면에 "왜 이 종목이 주목할 만한가" 텍스트를 렌더링하기 위한 데이터만 생성한다.
 *
 * 입력: alternative signals, 뉴스, watchlist, portfolio, AI 픽
 * 출력: 관심종목별 WatchAlert (severity/category/title/note/score/tags)
 */
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
                    note = "${item.name}이(가) ${formatSignedRate(item.changeRate)} 움직였습니다. 지금은 추격보다 뉴스 방향, 수급, 실험 지표가 같은 방향인지 먼저 보는 것이 맞습니다.",
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
                    note = "${item.name} 관련 뉴스가 ${relatedNews.size}건 묶였습니다. 가격 반응과 뉴스 방향이 엇갈리면 오히려 경계가 맞습니다.",
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
                    note = "${item.name}은(는) AI 추천 신뢰도 ${aiPick.confidence}, 기대수익률 ${"%.1f".format(aiPick.expectedReturnRate)}%입니다. 추천 근거와 오늘 뉴스 흐름이 같은 방향인지 확인해 보세요.",
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
                    note = "${item.name} 보유 수익률이 ${formatSignedRate(portfolioPosition.profitRate)}입니다. 익절/손절 기준과 오늘 뉴스 흐름을 같이 확인해 보세요.",
                    score = (kotlin.math.abs(portfolioPosition.profitRate) * 10).toInt().coerceIn(50, 94),
                    tags = listOf("보유종목", "${portfolioPosition.quantity}주", if (favorable) "익절체크" else "손실체크"),
                )
            }

            if (hotSignals.isNotEmpty() && item.market == "US" && (relatedNews.isNotEmpty() || aiPick != null)) {
                itemAlerts += WatchAlert(
                    severity = "medium", category = "signal",
                    market = item.market, ticker = item.ticker, name = item.name,
                    title = "실험 지표 과열 구간",
                    note = "${hotSignals.joinToString(", ")}가 같이 높아졌습니다. 미국 관심종목은 야간 뉴스와 정책 노이즈 영향을 크게 받을 수 있습니다.",
                    score = (60 + hotSignals.size * 8).coerceAtMost(90),
                    tags = listOf("실험지표", hotSignals.first(), item.market),
                )
            }

            // ── 기술적 지표 알림 ──────────────────────────────────────
            item.technical?.let { tech ->
                val rsi = tech.rsi
                if (rsi != null && (rsi >= 70 || rsi <= 30)) {
                    itemAlerts += WatchAlert(
                        severity = if (rsi >= 78 || rsi <= 22) "high" else "medium",
                        category = "technical",
                        market = item.market, ticker = item.ticker, name = item.name,
                        title = if (rsi >= 70) "RSI 과매수 구간" else "RSI 과매도 구간",
                        note = "${item.name} RSI-14가 ${"%.1f".format(rsi)}입니다. " +
                            if (rsi >= 70) "단기 과열 — 추격 진입보다 눌림/조정 대기가 유리합니다."
                            else "과매도권 진입 — 뉴스 방향과 수급이 맞으면 단기 반등 가능성을 체크해 보세요.",
                        score = (if (rsi >= 70) (rsi - 60) * 3 else (40 - rsi) * 3).toInt().coerceIn(48, 94),
                        tags = listOf("기술적", "RSI ${"%.0f".format(rsi)}", tech.rsiState ?: ""),
                    )
                }
                if (tech.maSignal == "GOLDEN" || tech.maSignal == "DEAD") {
                    itemAlerts += WatchAlert(
                        severity = "medium", category = "technical",
                        market = item.market, ticker = item.ticker, name = item.name,
                        title = if (tech.maSignal == "GOLDEN") "골든크로스 발생" else "데드크로스 발생",
                        note = "${item.name} MA5(${tech.ma5 ?: "-"}원)가 MA20(${tech.ma20 ?: "-"}원)을 " +
                            if (tech.maSignal == "GOLDEN") "상향 돌파했습니다. 추세 전환 초기 구간일 수 있습니다."
                            else "하향 돌파했습니다. 하락 추세 전환 신호 — 보유 중이라면 손절 기준을 재확인해 보세요.",
                        score = if (tech.maSignal == "GOLDEN") 72 else 76,
                        tags = listOf("기술적", tech.maSignal, "이동평균"),
                    )
                }
                val week52State = tech.week52State
                if (week52State == "신고가근접" || week52State == "신저가근접") {
                    val isHigh = week52State == "신고가근접"
                    itemAlerts += WatchAlert(
                        severity = if (isHigh) "medium" else "high",
                        category = "technical",
                        market = item.market, ticker = item.ticker, name = item.name,
                        title = if (isHigh) "52주 신고가 근접" else "52주 신저가 근접",
                        note = "${item.name}이(가) 52주 " +
                            if (isHigh) "고가(${tech.week52High}원) 2% 이내입니다. 돌파 여부와 거래량 확인이 중요합니다."
                            else "저가(${tech.week52Low}원) 3% 이내입니다. 추가 하락 리스크 점검이 필요합니다.",
                        score = if (isHigh) 68 else 82,
                        tags = listOf("기술적", week52State, "52주"),
                    )
                }
            }

            // ── 거래량 급증 알림 ──────────────────────────────────────
            val ratio = item.volumeRatio
            if (ratio != null && ratio >= 3.0) {
                itemAlerts += WatchAlert(
                    severity = if (ratio >= 5.0) "high" else "medium",
                    category = "volume",
                    market = item.market, ticker = item.ticker, name = item.name,
                    title = "거래량 급증",
                    note = "${item.name} 오늘 거래량이 평균 대비 ${"%.1f".format(ratio)}배입니다. 거래량이 터질 때는 방향 확인이 먼저입니다 — 상승이면 모멘텀, 하락이면 매도 압력.",
                    score = (ratio * 12).toInt().coerceIn(60, 95),
                    tags = listOf("거래량", "${"%.1f".format(ratio)}배", item.market),
                )
            }

            // ── 목표가/손절가 근접 알림 ──────────────────────────────
            if (portfolioPosition != null) {
                portfolioPosition.targetPrice?.let { target ->
                    if (target > 0 && item.price.toDouble() / target >= 0.97) {
                        itemAlerts += WatchAlert(
                            severity = "high", category = "target",
                            market = item.market, ticker = item.ticker, name = item.name,
                            title = "목표가 근접",
                            note = "${item.name} 현재가(${item.price}원)가 목표가(${target}원) 3% 이내입니다. 익절 타이밍을 검토해 보세요.",
                            score = 88,
                            tags = listOf("목표가", "${target}원", "익절체크"),
                        )
                    }
                }
                portfolioPosition.stopLossPrice?.let { stopLoss ->
                    if (stopLoss > 0 && item.price.toDouble() / stopLoss <= 1.03) {
                        itemAlerts += WatchAlert(
                            severity = "high", category = "target",
                            market = item.market, ticker = item.ticker, name = item.name,
                            title = "손절가 근접",
                            note = "${item.name} 현재가(${item.price}원)가 손절가(${stopLoss}원) 3% 이내입니다. 손절 실행 여부를 즉시 판단해 주세요.",
                            score = 93,
                            tags = listOf("손절가", "${stopLoss}원", "리스크"),
                        )
                    }
                }
            }

            itemAlerts
        }

        val concentrationAlerts = buildConcentrationAlerts(portfolio)

        return (alerts + concentrationAlerts)
            .sortedWith(
                compareByDescending<WatchAlert> { alertSeverityRank(it.severity) }
                    .thenByDescending { alertCategoryRank(it) }
                    .thenByDescending { it.score }
                    .thenBy { it.market }
                    .thenBy { it.ticker }
            )
            .distinctBy { "${it.market}:${it.ticker}" }
            .take(8)
    }

    private fun buildConcentrationAlerts(portfolio: PortfolioSummary): List<WatchAlert> {
        if (portfolio.positions.isEmpty() || portfolio.totalValue <= 0) return emptyList()
        val alerts = mutableListOf<WatchAlert>()

        portfolio.positions.forEach { position ->
            val weight = position.evaluationAmount.toDouble() / portfolio.totalValue * 100
            if (weight >= 40.0) {
                alerts += WatchAlert(
                    severity = if (weight >= 60) "high" else "medium",
                    category = "concentration",
                    market = position.market, ticker = position.ticker, name = position.name,
                    title = "포트폴리오 집중도 경고",
                    note = "${position.name}이(가) 포트폴리오의 ${"%.1f".format(weight)}%를 차지합니다. 단일 종목 집중은 리스크가 큽니다 — 분산 또는 비중 축소를 고려해 보세요.",
                    score = (weight * 1.2).toInt().coerceIn(60, 95),
                    tags = listOf("집중도", "${"%.0f".format(weight)}%", "리밸런싱"),
                )
            }
        }

        val sectorGroups = portfolio.positions.groupBy { it.market }
        sectorGroups.forEach { (market, positions) ->
            val marketWeight = positions.sumOf { it.evaluationAmount }.toDouble() / portfolio.totalValue * 100
            if (marketWeight >= 80.0 && portfolio.positions.size >= 3) {
                alerts += WatchAlert(
                    severity = "medium", category = "concentration",
                    market = market, ticker = "PORTFOLIO", name = "포트폴리오",
                    title = "$market 시장 편중",
                    note = "포트폴리오의 ${"%.1f".format(marketWeight)}%가 $market 시장에 집중돼 있습니다. 다른 시장 분산 비중 검토가 필요합니다.",
                    score = (marketWeight * 0.8).toInt().coerceIn(55, 80),
                    tags = listOf("시장편중", market, "${"%.0f".format(marketWeight)}%"),
                )
            }
        }
        return alerts
    }

    private fun isRelatedNews(item: WatchItem, news: MarketNews): Boolean {
        val title = news.title.lowercase()
        return title.contains(item.name.lowercase()) || title.contains(item.ticker.lowercase())
    }

    private fun estimateNewsImpact(news: MarketNews): Int {
        val text = (news.impact + " " + news.title).lowercase()
        var score = 20
        // 고임팩트 키워드
        val highImpact = listOf("쇼크", "급락", "폭락", "서킷브레이커", "파산", "제재", "전쟁", "긴급", "폭등", "급등", "신고가", "어닝서프라이즈")
        val medHighImpact = listOf("강세", "호재", "수혜", "상향", "실적", "돌파", "기대", "반등", "회복")
        val medLowImpact = listOf("경계", "약세", "하향", "부진", "감소", "축소", "리스크", "우려")
        val lowImpact = listOf("관망", "보합", "횡보", "혼조", "소폭")
        score += highImpact.count { text.contains(it) } * 12
        score += medHighImpact.count { text.contains(it) } * 8
        score += medLowImpact.count { text.contains(it) } * 6
        score += lowImpact.count { text.contains(it) } * 3
        return score.coerceIn(20, 50)
    }

    private fun alertSeverityRank(severity: String) = when (severity) { "high" -> 3; "medium" -> 2; else -> 1 }

    private fun alertCategoryRank(alert: WatchAlert) = when {
        alert.category == "target" && alert.title.contains("손절") -> 9
        alert.category == "target" -> 8
        alert.category == "portfolio" && alert.title.contains("손실") -> 7
        alert.category == "news" -> 6
        alert.category == "price" -> 5
        alert.category == "volume" -> 4
        alert.category == "technical" -> 3
        alert.category == "ai" -> 2
        alert.category == "signal" -> 1
        else -> 0
    }

    fun formatSignedRate(value: Double): String = if (value > 0) "+${"%.2f".format(value)}%" else "${"%.2f".format(value)}%"
}
