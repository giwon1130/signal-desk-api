package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Component

/**
 * 시세를 받아 워크스페이스 항목(관심/포트폴리오/AI/모의투자)을 갱신하는 순수 변환 모음.
 * WorkspaceEnrichmentService에서 분리 — 외부 시세를 입력 받아 도메인을 재계산.
 */
@Component
class WorkspaceQuoteRefresher(
    private val chartClient: NaverStockChartClient,
    private val technicalCalculator: TechnicalIndicatorCalculator,
) {

    fun refreshKoreanLeadingStocks(stocks: List<TickerSnapshot>, quotes: Map<String, StockQuote>): List<TickerSnapshot> =
        stocks.map { stock ->
            quotes[stock.ticker]?.let { quote ->
                stock.copy(price = quote.currentPrice, changeRate = quote.changeRate)
            } ?: stock
        }

    fun refreshWatchlist(items: List<WatchItem>, quotes: Map<String, StockQuote>): List<WatchItem> =
        items.map { item ->
            if (item.market != "KR") return@map item
            val quote = quotes[item.ticker] ?: return@map item
            val bars = chartClient.fetchDailyBars(item.ticker, count = 30)
            val technical = technicalCalculator.calculate(bars)
            val volumeRatio = technicalCalculator.volumeRatio(bars)
            item.copy(
                price = quote.currentPrice,
                changeRate = quote.changeRate,
                technical = technical,
                volume = bars.lastOrNull()?.volume ?: 0L,
                volumeRatio = volumeRatio,
            )
        }.sortedWith(compareBy({ it.market }, { it.name }))

    fun refreshPortfolio(portfolio: PortfolioSummary, quotes: Map<String, StockQuote>): PortfolioSummary {
        val previousEvaluation = portfolio.positions.associateBy({ it.ticker }) { it.evaluationAmount }
        val positions = portfolio.positions.map { position ->
            if (position.market != "KR") return@map position
            val quote = quotes[position.ticker] ?: return@map position
            val evaluationAmount = quote.currentPrice.toLong() * position.quantity
            val costAmount = position.buyPrice.toLong() * position.quantity
            val profitAmount = evaluationAmount - costAmount
            position.copy(
                currentPrice = quote.currentPrice, profitAmount = profitAmount,
                evaluationAmount = evaluationAmount,
                profitRate = if (costAmount == 0L) 0.0 else (profitAmount.toDouble() / costAmount) * 100,
            )
        }
        val delta = positions.sumOf { position ->
            position.evaluationAmount - (previousEvaluation[position.ticker] ?: position.evaluationAmount)
        }
        val totalValue = portfolio.totalValue + delta
        val totalProfit = totalValue - portfolio.totalCost
        return portfolio.copy(
            totalValue = totalValue, totalProfit = totalProfit,
            totalProfitRate = if (portfolio.totalCost == 0L) 0.0 else (totalProfit.toDouble() / portfolio.totalCost) * 100,
            positions = positions.sortedWith(compareBy({ it.market }, { it.name })),
        )
    }

    fun refreshAiRecommendations(section: AIRecommendationSection, quotes: Map<String, StockQuote>): AIRecommendationSection {
        val refreshedTrackRecords = section.trackRecords.map { record ->
            if (record.market != "KR") return@map record
            val quote = quotes[record.ticker] ?: return@map record
            val realizedReturnRate = if (record.entryPrice == 0) 0.0
            else ((quote.currentPrice - record.entryPrice).toDouble() / record.entryPrice) * 100
            record.copy(latestPrice = quote.currentPrice, realizedReturnRate = realizedReturnRate, success = realizedReturnRate >= 0)
        }
        return section.copy(
            trackRecords = refreshedTrackRecords,
            executionLogs = buildExecutionLogs(section.generatedDate, section.picks, refreshedTrackRecords, quotes),
        )
    }


    fun buildExecutionLogs(
        generatedDate: String,
        picks: List<RecommendationPick>,
        trackRecords: List<RecommendationTrackRecord>,
        quotes: Map<String, StockQuote>,
    ): List<RecommendationExecutionLog> {
        val resultByTicker = trackRecords.associateBy { "${it.market}:${it.ticker}" }
        val pickLogs = picks.map { pick ->
            val match = resultByTicker["${pick.market}:${pick.ticker}"]
            val (entry, stop, target) = computeTradeGuide(pick.market, pick.ticker, pick.expectedReturnRate, quotes)
            RecommendationExecutionLog(
                date = generatedDate, market = pick.market, ticker = pick.ticker, name = pick.name,
                stage = "RECOMMEND", status = if (match != null) "검증완료" else "추적중",
                rationale = "${pick.basis} · ${pick.note}", confidence = pick.confidence,
                expectedReturnRate = pick.expectedReturnRate, realizedReturnRate = match?.realizedReturnRate,
                source = pick.source,
                entryPrice = entry, stopLoss = stop, takeProfit = target,
            )
        }
        val resultLogs = trackRecords
            .filter { record -> picks.none { it.market == record.market && it.ticker == record.ticker } }
            .map { record ->
                RecommendationExecutionLog(
                    date = record.recommendedDate, market = record.market, ticker = record.ticker, name = record.name,
                    stage = "RESULT", status = if (record.success) "검증완료" else "재평가필요",
                    rationale = "추천 성과 기록", confidence = null, expectedReturnRate = null,
                    realizedReturnRate = record.realizedReturnRate, source = record.source,
                )
            }
        return (pickLogs + resultLogs)
            .sortedWith(compareByDescending<RecommendationExecutionLog> { it.date }.thenBy { it.market }.thenBy { it.ticker })
            .take(30)
    }

    /**
     * 진입/손절/목표가 — 단타 가이드.
     *  · entry = 라이브 현재가
     *  · stop  = entry × (1 - 0.025)
     *  · target = entry × (1 + clamp(expectedReturnRate%, 3..20) / 100)
     */
    private fun computeTradeGuide(
        market: String,
        ticker: String,
        expectedReturnRate: Double?,
        quotes: Map<String, StockQuote>,
    ): Triple<Int?, Int?, Int?> {
        if (market != "KR") return Triple(null, null, null)
        val entry = quotes[ticker]?.currentPrice?.takeIf { it > 0 } ?: return Triple(null, null, null)
        val stop = (entry * (1.0 - 0.025)).toInt()
        val target = expectedReturnRate?.let {
            val pct = it.coerceIn(3.0, 20.0)
            (entry * (1.0 + pct / 100.0)).toInt()
        }
        return Triple(entry, stop, target)
    }
}
