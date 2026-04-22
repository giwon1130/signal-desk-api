package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

/**
 * 시장 데이터(AI 추천, 실험 지표)를 사용자의 워크스페이스 상태와 연결해
 * "내 포지션 기준으로 이건 어떤 의미?"를 한 줄로 붙인다.
 *
 * - RecommendationPick/ExecutionLog.userStatus: HELD | WATCHED | NEW
 * - AlternativeSignal.personalImpact: 내 관심/보유 종목과 연결된 해석
 */
@Service
class PersonalContextAnnotator {

    fun annotateRecommendations(
        ai: AIRecommendationSection,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
    ): AIRecommendationSection {
        val heldKeys = portfolio.positions.map { it.key() }.toSet()
        val watchedKeys = watchlist.map { it.key() }.toSet()

        val picks = ai.picks.map { pick ->
            pick.copy(userStatus = resolveStatus(pick.key(), heldKeys, watchedKeys))
        }
        val logs = ai.executionLogs.map { log ->
            log.copy(userStatus = resolveStatus(log.key(), heldKeys, watchedKeys))
        }
        return ai.copy(picks = picks, executionLogs = logs)
    }

    fun annotateAlternativeSignals(
        signals: List<AlternativeSignal>,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
    ): List<AlternativeSignal> = signals.map { signal ->
        val matches = findMatchingHoldings(signal, watchlist, portfolio)
        signal.copy(personalImpact = buildImpactLine(signal, matches))
    }

    private fun resolveStatus(key: String, held: Set<String>, watched: Set<String>): String = when {
        key in held -> "HELD"
        key in watched -> "WATCHED"
        else -> "NEW"
    }

    private fun findMatchingHoldings(
        signal: AlternativeSignal,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
    ): List<Match> {
        val keywords = keywordsFor(signal.label)
        if (keywords.isEmpty()) return emptyList()

        val matches = mutableListOf<Match>()
        portfolio.positions.forEach { pos ->
            if (keywords.any { pos.name.contains(it, ignoreCase = true) }) {
                matches += Match(name = pos.name, ticker = pos.ticker, isHeld = true)
            }
        }
        watchlist.forEach { item ->
            if (item.ticker in matches.map { it.ticker }) return@forEach
            val hay = "${item.name} ${item.sector}"
            if (keywords.any { hay.contains(it, ignoreCase = true) }) {
                matches += Match(name = item.name, ticker = item.ticker, isHeld = false)
            }
        }
        return matches.take(3)
    }

    private fun keywordsFor(label: String): List<String> {
        val lower = label.lowercase()
        return when {
            // Pentagon Pizza Index: 국방부 주변 피자 소비 급증 = 지정학 긴장 신호
            "pentagon" in lower || "pizza" in lower -> listOf("국방", "방산", "LMT", "Lockheed", "Northrop", "RTX", "Raytheon", "한화에어로스페이스", "한국항공우주", "현대로템")
            // Policy Buzz: 정책 관련 뉴스 빈도 = 규제 민감주
            "policy" in lower || "정책" in lower -> listOf("금융", "은행", "증권", "보험", "제약", "바이오")
            // Bar Counter-Signal: 술집 매출 역지표 = 내수 소비
            "bar" in lower || "카운터" in lower || "내수" in lower -> listOf("소비재", "유통", "외식", "편의점", "주류", "면세")
            else -> emptyList()
        }
    }

    private fun buildImpactLine(signal: AlternativeSignal, matches: List<Match>): String? {
        if (matches.isEmpty()) return null
        val hot = signal.score >= 70
        val held = matches.filter { it.isHeld }
        val watched = matches.filter { !it.isHeld }

        val subjects = buildList {
            if (held.isNotEmpty()) add("보유 ${held.joinToString(", ") { it.name }}")
            if (watched.isNotEmpty()) add("관심 ${watched.joinToString(", ") { it.name }}")
        }.joinToString(" · ")

        val verdict = when {
            hot && held.isNotEmpty() -> "수혜 가능성 — 보유분 포지션 점검"
            hot -> "지표 과열 — 관심종목 진입 타이밍 주의"
            else -> "지표 낮음 — 관망"
        }
        return "$subjects — $verdict"
    }

    private data class Match(val name: String, val ticker: String, val isHeld: Boolean)

    private fun WatchItem.key() = "$market:$ticker"
    private fun HoldingPosition.key() = "$market:$ticker"
    private fun RecommendationPick.key() = "$market:$ticker"
    private fun RecommendationExecutionLog.key() = "$market:$ticker"
}
