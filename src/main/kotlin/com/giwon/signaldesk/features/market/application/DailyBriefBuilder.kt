package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 시장 데이터 + 워크스페이스 상태 + 현재 시각(장 전/장중/마감/휴장)을 조합해
 * 사용자 개인화 브리핑을 조립한다.
 *
 * Today 탭 최상단 카드가 이 결과를 그대로 렌더링한다.
 */
@Service
class DailyBriefBuilder(
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {

    fun build(
        base: DailyBriefing,
        watchAlerts: List<WatchAlert>,
        portfolio: PortfolioSummary,
        aiRecommendations: AIRecommendationSection,
        marketSummary: List<SummaryMetric>,
        alternativeSignals: List<AlternativeSignal>,
        tradingDay: TradingDayStatus,
    ): DailyBriefing {
        val slot = resolveSlot(tradingDay)
        val context = buildContext(portfolio, watchAlerts, marketSummary, alternativeSignals)
        val actions = buildActions(watchAlerts, portfolio, aiRecommendations).take(3)
        val narrative = buildNarrative(slot, context, portfolio, watchAlerts.size)

        val preMarketBullets = buildPreMarketBullets(watchAlerts, base.preMarket)
        val afterMarketBullets = buildAfterMarketBullets(watchAlerts, base.afterMarket)

        return base.copy(
            narrative = narrative,
            slot = slot.name,
            context = context,
            actionItems = actions,
            preMarket = preMarketBullets,
            afterMarket = afterMarketBullets,
        )
    }

    private fun resolveSlot(tradingDay: TradingDayStatus): BriefingSlot {
        if (tradingDay.isWeekend) return BriefingSlot.WEEKEND
        if (tradingDay.isHoliday) return BriefingSlot.HOLIDAY
        val now = ZonedDateTime.now(clock)
        val minutesOfDay = now.hour * 60 + now.minute
        return when {
            minutesOfDay < 9 * 60 -> BriefingSlot.PRE_MARKET
            minutesOfDay < 15 * 60 + 30 -> BriefingSlot.INTRADAY
            else -> BriefingSlot.POST_MARKET
        }
    }

    private fun buildContext(
        portfolio: PortfolioSummary,
        watchAlerts: List<WatchAlert>,
        marketSummary: List<SummaryMetric>,
        alternativeSignals: List<AlternativeSignal>,
    ): BriefingContext {
        val holdingPnlLabel: String?
        val holdingPnlRate: Double?
        if (portfolio.positions.isEmpty()) {
            holdingPnlLabel = null
            holdingPnlRate = null
        } else {
            holdingPnlLabel = "${formatSignedAmount(portfolio.totalProfit.toDouble())} (${formatSignedRate(portfolio.totalProfitRate)})"
            holdingPnlRate = portfolio.totalProfitRate
        }

        val mood = resolveMarketMood(marketSummary)
        val hotSignals = alternativeSignals.filter { it.score >= 70 }
        val fearMetric = marketSummary.firstOrNull { it.label == "Fear Meter" }
        val keyEvent = when {
            hotSignals.isNotEmpty() -> hotSignals.joinToString(" · ") { "${it.label} ${it.score.toInt()}" }
            fearMetric != null && fearMetric.score.toDouble() <= 35 -> "공포 확대 — ${fearMetric.note}"
            else -> null
        }

        return BriefingContext(
            holdingPnlLabel = holdingPnlLabel,
            holdingPnlRate = holdingPnlRate,
            watchlistAlertCount = watchAlerts.size,
            marketMood = mood,
            keyEvent = keyEvent,
        )
    }

    private fun resolveMarketMood(marketSummary: List<SummaryMetric>): String {
        val fear = marketSummary.firstOrNull { it.label == "Fear Meter" }?.score?.toDouble() ?: 50.0
        val usHeat = marketSummary.firstOrNull { it.label == "US Heat" }?.score?.toDouble() ?: 50.0
        val krHeat = marketSummary.firstOrNull { it.label == "KR Heat" }?.score?.toDouble() ?: 50.0
        val avgHeat = (usHeat + krHeat) / 2
        return when {
            fear <= 30 -> "방어적"
            fear >= 70 && avgHeat >= 60 -> "위험선호 우위"
            avgHeat >= 60 -> "공격적"
            avgHeat <= 40 -> "경계"
            else -> "관망"
        }
    }

    private fun buildActions(
        watchAlerts: List<WatchAlert>,
        portfolio: PortfolioSummary,
        aiRecommendations: AIRecommendationSection,
    ): List<BriefingAction> {
        val actions = mutableListOf<BriefingAction>()

        watchAlerts.forEach { alert ->
            actions += BriefingAction(
                priority = alert.severity,
                category = alert.category,
                title = "${alert.name} · ${alert.title}",
                detail = alert.note,
                ticker = alert.ticker,
                market = alert.market,
            )
        }

        if (portfolio.positions.isNotEmpty() && actions.none { it.category == "portfolio" }) {
            val worst = portfolio.positions.minByOrNull { it.profitRate }
            if (worst != null && worst.profitRate <= -3.0) {
                actions += BriefingAction(
                    priority = if (worst.profitRate <= -7.0) "high" else "medium",
                    category = "portfolio",
                    title = "${worst.name} 손실 구간 점검",
                    detail = "수익률 ${formatSignedRate(worst.profitRate)} — 손절 기준과 오늘 뉴스 방향 같이 확인.",
                    ticker = worst.ticker,
                    market = worst.market,
                )
            }
        }

        val topPick = aiRecommendations.picks
            .filter { it.confidence >= 75 }
            .maxByOrNull { it.confidence * it.expectedReturnRate }
        if (topPick != null && actions.none { it.ticker == topPick.ticker }) {
            actions += BriefingAction(
                priority = "medium",
                category = "ai",
                title = "${topPick.name} AI 추천 확인",
                detail = "신뢰도 ${topPick.confidence}, 기대수익률 ${formatSignedRate(topPick.expectedReturnRate)} — 근거: ${topPick.basis}",
                ticker = topPick.ticker,
                market = topPick.market,
            )
        }

        return actions
    }

    private fun buildNarrative(
        slot: BriefingSlot,
        context: BriefingContext,
        portfolio: PortfolioSummary,
        totalAlerts: Int,
    ): String {
        val openingPhrase = when (slot) {
            BriefingSlot.PRE_MARKET -> "아시아 개장 전입니다."
            BriefingSlot.INTRADAY -> "한국장 진행 중입니다."
            BriefingSlot.POST_MARKET -> "정규장 마감 후입니다."
            BriefingSlot.WEEKEND -> "주말 휴장입니다."
            BriefingSlot.HOLIDAY -> "오늘은 휴장일입니다."
        }

        val moodPhrase = "시장 분위기는 '${context.marketMood}'"
            .let { base ->
                context.keyEvent?.let { "$base, $it" } ?: base
            } + "."

        val portfolioPhrase = when {
            portfolio.positions.isEmpty() -> "보유 종목은 없고, 관심종목 ${totalAlerts}건의 신호가 대기 중입니다."
            context.holdingPnlLabel != null -> "내 보유 ${context.holdingPnlLabel}, 관심 신호 ${totalAlerts}건."
            else -> "관심 신호 ${totalAlerts}건 대기."
        }

        val actionPhrase = when (slot) {
            BriefingSlot.PRE_MARKET -> "개장 직후 추격보다는 시가·거래량부터 확인하세요."
            BriefingSlot.INTRADAY -> "장중 급변동은 뉴스 방향과 같이 확인하고, 목표가 없으면 진입 보류가 기본입니다."
            BriefingSlot.POST_MARKET -> "오늘 결과와 AI 추천 복기로 내일 후보를 1~2개만 추려두세요."
            BriefingSlot.WEEKEND -> "신규 진입 없음. 관심종목 정리, 손절/익절 라인 재설정에만 집중하세요."
            BriefingSlot.HOLIDAY -> "체결이 없어 시나리오 점검만. 다음 거래일 준비."
        }

        return "$openingPhrase $moodPhrase $portfolioPhrase $actionPhrase"
    }

    private fun buildPreMarketBullets(alerts: List<WatchAlert>, fallback: List<String>): List<String> {
        val topAlerts = alerts.take(2).map { alert ->
            when (alert.category) {
                "portfolio" -> "${alert.name}: 보유 ${alert.title} · ${alert.tags.joinToString(" / ")}"
                "ai" -> "${alert.name}: AI 추천 정렬 확인 · ${alert.tags.joinToString(" / ")}"
                else -> "${alert.name}: ${alert.title} · ${alert.tags.joinToString(" / ")}"
            }
        }
        return (topAlerts + fallback).distinct().take(5)
    }

    private fun buildAfterMarketBullets(alerts: List<WatchAlert>, fallback: List<String>): List<String> {
        val items = alerts.drop(1).take(2).map { alert ->
            "${alert.name}: 오늘 ${labelCategory(alert.category)} 신호가 실제 수익/손실과 연결됐는지 복기"
        }
        return (items + fallback).distinct().take(5)
    }

    private fun labelCategory(category: String) = when (category) {
        "portfolio" -> "보유"; "news" -> "뉴스"; "price" -> "가격"; "ai" -> "AI"; "signal" -> "실험"; else -> category
    }

    private fun formatSignedRate(value: Double): String =
        if (value > 0) "+${"%.2f".format(value)}%" else "${"%.2f".format(value)}%"

    private fun formatSignedAmount(value: Double): String {
        val rounded = value.toLong()
        val sign = if (rounded >= 0) "+" else "-"
        return "$sign₩${"%,d".format(kotlin.math.abs(rounded))}"
    }
}

enum class BriefingSlot { PRE_MARKET, INTRADAY, POST_MARKET, WEEKEND, HOLIDAY }
