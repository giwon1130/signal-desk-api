package com.giwon.signaldesk.features.rounds.application

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import com.giwon.signaldesk.features.market.application.StockQuote
import com.giwon.signaldesk.features.media.application.YoutubeChannelClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class SemiconductorRoundAutomationServiceTest {
    private val service = SemiconductorRoundAutomationService(
        rounds = mock(MarketRoundService::class.java),
        krQuotes = mock(NaverFinanceQuoteClient::class.java),
        usQuotes = mock(NaverGlobalQuoteClient::class.java),
        youtube = mock(YoutubeChannelClient::class.java),
        enabled = true,
        krDropThreshold = -4.0,
        usDropThreshold = -5.0,
        youtubeChannelId = "",
        youtubeSourceName = "삼프로TV",
    )

    @Test
    fun `KR 종목 평균이 임계치보다 낮을 때만 급락 신호다`() {
        assertTrue(service.isDropSignal("KR", mapOf(
            "005930" to quote("005930", -4.2),
            "000660" to quote("000660", -4.0),
        )))
        assertFalse(service.isDropSignal("KR", mapOf(
            "005930" to quote("005930", -5.0),
            "000660" to quote("000660", -2.0),
        )))
    }

    @Test
    fun `US는 핵심 칩주 둘 이상이 임계치 아래여야 한다`() {
        assertTrue(service.isDropSignal("US", mapOf(
            "NVDA" to quote("NVDA", -5.1),
            "AMD" to quote("AMD", -6.0),
            "MU" to quote("MU", -1.0),
        )))
        assertFalse(service.isDropSignal("US", mapOf(
            "NVDA" to quote("NVDA", -6.0),
            "AMD" to quote("AMD", -2.0),
            "MU" to quote("MU", -1.0),
        )))
    }

    @Test
    fun `반도체 관련 키워드가 있는 공식 영상 제목만 선택한다`() {
        assertTrue(service.containsSemiconductorKeyword("반도체, 정말 꺾였나"))
        assertTrue(service.containsSemiconductorKeyword("NVIDIA 실적 이후 시장"))
        assertFalse(service.containsSemiconductorKeyword("오늘의 환율과 채권 시장"))
    }

    private fun quote(ticker: String, changeRate: Double) = StockQuote(ticker, 100, changeRate)
}
