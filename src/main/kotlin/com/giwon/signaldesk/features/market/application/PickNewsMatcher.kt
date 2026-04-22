package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

/**
 * 추천 픽/실행 로그를 뉴스 헤드라인과 자동 매칭한다.
 *
 * 기존 title.contains(name) 전략은 짧은 종목명(예: "SK", "한전")에서 오매칭이 심했다.
 * 개선안:
 *   1) 티커가 헤드라인에 "단어 경계"로 등장하면 최우선 매칭 (가장 신뢰도 높음)
 *   2) 이름이 2글자 이상이고 title에 포함되면 매칭
 *   3) 이름이 2글자 미만이거나 아주 짧으면 매칭 생략 (오매칭 방지)
 *   4) 같은 market 뉴스를 우선, 없으면 전체로 fallback
 */
@Service
class PickNewsMatcher {

    private val minNameLength = 2

    fun findMatch(market: String, name: String, ticker: String, news: List<MarketNews>): MarketNews? {
        if (news.isEmpty()) return null
        val scoped = news.filter { it.market == market }.ifEmpty { news }

        val tickerMatch = scoped.firstOrNull { isTickerInTitle(ticker, it.title) }
        if (tickerMatch != null) return tickerMatch

        if (name.length < minNameLength) return null
        return scoped.firstOrNull { it.title.contains(name, ignoreCase = true) }
    }

    /** 티커가 title 내에서 단어 경계에 등장하는지. 영문 티커(AAPL)는 `\b`, 숫자 티커(005930)는 그대로 contains. */
    private fun isTickerInTitle(ticker: String, title: String): Boolean {
        if (ticker.isBlank()) return false
        // 한국 숫자 티커는 6자리라 거의 unique — contains 로 충분
        if (ticker.all { it.isDigit() }) return title.contains(ticker)
        // 영문 티커는 단어 경계 검사 — "AA" 가 "AAPL" 과 섞이지 않도록
        val pattern = "(?i)\\b${Regex.escape(ticker)}\\b".toRegex()
        return pattern.containsMatchIn(title)
    }
}
