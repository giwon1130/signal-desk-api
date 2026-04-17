package com.giwon.signaldesk.features.kakao

import org.springframework.stereotype.Component

@Component
class KakaoMessageParser {

    // "삼성전자 70000에 샀어", "005930 70000 10주 매수", "카카오 50000원 5주 구매"
    private val tradePattern = Regex(
        """^(.+?)\s+(\d[\d,]*)\s*원?\s*(?:에\s*)?(\d+)?\s*주?\s*(샀어|매수|구매|샀다|매수했어|샀음)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(utterance: String): KakaoIntent {
        val text = utterance.trim()
        return when {
            tradePattern.containsMatchIn(text) -> KakaoIntent.SAVE_TRADE
            text.contains(Regex("내 현황|포트폴리오|보유|잔고|수익률")) -> KakaoIntent.PORTFOLIO
            text.contains(Regex("체크|매도|팔아야|팔까|손절|익절|파는 시기|팔때|팔때가")) -> KakaoIntent.SELL_CHECK
            text.contains(Regex("추천|단타|뭐 살까|뭐살까|살만한|오늘 종목|살거|살까")) -> KakaoIntent.RECOMMEND
            text.contains(Regex("리포트|오늘 분석|일일|전체 보고|오늘 보고")) -> KakaoIntent.DAILY_REPORT
            else -> KakaoIntent.UNKNOWN
        }
    }

    fun parseTrade(utterance: String): TradeParseResult? {
        val match = tradePattern.find(utterance.trim()) ?: return null
        val query = match.groupValues[1].trim()
        val price = match.groupValues[2].replace(",", "").toIntOrNull() ?: return null
        val quantity = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 1
        return TradeParseResult(query = query, price = price, quantity = quantity)
    }
}
