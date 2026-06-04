package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompositeRiskServiceTest {

    private val service = CompositeRiskService()

    // ─── 헬퍼 ───────────────────────────────────────────────────────────────
    private fun pizzSignals(pizza: Int = 60, policy: Int = 60, bar: Int = 60): List<AlternativeSignal> =
        listOf(
            altSignal("Pentagon Pizza Index", pizza),
            altSignal("Policy Buzz", policy),
            altSignal("Bar Counter-Signal", bar),
        )

    private fun altSignal(label: String, score: Int) = AlternativeSignal(
        label = label, score = score, state = "테스트", note = "",
        highlights = emptyList(), source = "test", url = "", experimental = true,
    )

    private fun news(title: String, market: String = "US") = MarketNews(
        market = market, title = title, source = "test", url = "http://x", impact = "",
    )

    private fun vix(price: Double, change: Double = 0.0) = VixSnapshot(
        currentPrice = price, priceChange = change, lastTradeTime = "2026-05-27T20:00:00Z",
    )

    private fun krMarket(vararg changeRates: Double) = MarketSection(
        market = "KR", title = "한국",
        indices = changeRates.mapIndexed { i, ch -> IndexMetric("지수$i", 1000.0, ch, emptyList()) },
        sentiment = emptyList(), investorFlows = emptyList(), leadingStocks = emptyList(),
    )

    private val emptyPortfolio = PortfolioSummary(0, 0, 0, 0.0, emptyList())

    private fun heldPortfolio() = PortfolioSummary(
        totalCost = 1_000_000, totalValue = 1_100_000, totalProfit = 100_000, totalProfitRate = 10.0,
        positions = listOf(
            HoldingPosition(
                market = "KR", ticker = "005930", name = "삼성전자",
                buyPrice = 70000, currentPrice = 80000, quantity = 10,
                profitAmount = 100_000, evaluationAmount = 800_000, profitRate = 14.3,
            ),
        ),
    )

    // ─── 합성 점수 범위 ─────────────────────────────────────────────────────
    @Test
    fun `모든 입력 null·empty 일 때 score 1~10 범위 + 중립 컴포넌트`() {
        val result = service.build(emptyList(), null, emptyList(), emptyList(), emptyPortfolio)

        assertTrue(result.score in 1..10, "score=${result.score}")
        assertTrue(result.score100 in 0..100, "score100=${result.score100}")
        assertEquals(4, result.components.size)
        // VIX/한국지수/뉴스 컴포넌트는 데이터 없을 때 "데이터 대기" 라벨
        assertTrue(result.components.any { it.state == "데이터 대기" })
    }

    // ─── VIX 컴포넌트 ───────────────────────────────────────────────────────
    @Test
    fun `VIX 36 이상이면 위험 100점`() {
        val result = service.build(emptyList(), vix(40.0), emptyList(), emptyList(), emptyPortfolio)
        val vixComp = result.components.first { it.label == "VIX 변동성" }
        assertEquals(100, vixComp.score)
        assertEquals("변동성 경계", vixComp.state)
    }

    @Test
    fun `VIX 13 이하이면 위험 0점 (또는 그에 가까움)`() {
        val result = service.build(emptyList(), vix(10.0), emptyList(), emptyList(), emptyPortfolio)
        val vixComp = result.components.first { it.label == "VIX 변동성" }
        // 13 미만이면 raw 가 음수가 되어 coerce 로 0 으로 clamp.
        assertTrue(vixComp.score <= 5, "score=${vixComp.score}")
        assertEquals("안정", vixComp.state)
    }

    @Test
    fun `VIX 전일 대비 급등하면 trendBonus 가산`() {
        // 같은 현재가, 전일 대비 +5 → +10 bonus 가 score 에 반영.
        val flat = service.build(emptyList(), vix(20.0, 0.0), emptyList(), emptyList(), emptyPortfolio)
            .components.first { it.label == "VIX 변동성" }.score
        val rising = service.build(emptyList(), vix(20.0, 5.0), emptyList(), emptyList(), emptyPortfolio)
            .components.first { it.label == "VIX 변동성" }.score
        assertTrue(rising > flat, "rising=$rising flat=$flat")
    }

    // ─── 한국 지수 변동 컴포넌트 ─────────────────────────────────────────────
    @Test
    fun `한국 지수 급락하면 KR 컴포넌트 높음`() {
        // KOSPI -3% → 낙폭×32 = 96 → '급변동 경계'. (이전엔 KR 입력 자체가 없어 합성에서 빠졌다)
        val result = service.build(emptyList(), null, emptyList(), emptyList(), emptyPortfolio, krMarket(-3.0, 1.0))
        val kr = result.components.first { it.label == "한국 지수 변동" }
        assertTrue(kr.score >= 70, "score=${kr.score}")
        assertEquals("급변동 경계", kr.state)
    }

    @Test
    fun `한국 지수 잔잔하면 KR 컴포넌트 낮음`() {
        val result = service.build(emptyList(), null, emptyList(), emptyList(), emptyPortfolio, krMarket(0.2, -0.1))
        val kr = result.components.first { it.label == "한국 지수 변동" }
        assertTrue(kr.score < 40, "score=${kr.score}")
        assertEquals("안정", kr.state)
    }

    @Test
    fun `한국 급락이 합성 위험도를 끌어올린다 (미국이 잠잠해도)`() {
        // 미국은 잠잠(VIX 14)인데 한국만 급락(-3%): 이전엔 VIX 낮으면 '안정'이었지만 이제 KR이 위험도를 올린다.
        val calmUsOnly = service.build(emptyList(), vix(14.0), emptyList(), emptyList(), emptyPortfolio)
        val krCrash = service.build(emptyList(), vix(14.0), emptyList(), emptyList(), emptyPortfolio, krMarket(-3.0, -2.5))
        assertTrue(krCrash.score100 > calmUsOnly.score100 + 10,
            "krCrash=${krCrash.score100} vs calmUs=${calmUsOnly.score100}")
    }

    // ─── PizzINT 컴포넌트 (recenter) ────────────────────────────────────────
    @Test
    fun `PizzINT raw 가 FLOOR(48) 이하면 score 0`() {
        // 모든 신호 0 → rawBlend 0 → recenter 후 음수 → 0 으로 clamp.
        val result = service.build(pizzSignals(0, 0, 0), null, emptyList(), emptyList(), emptyPortfolio)
        val pizz = result.components.first { it.label == "PizzINT 종합" }
        assertEquals(0, pizz.score)
        assertEquals("잠잠", pizz.state)
    }

    @Test
    fun `PizzINT raw 가 CEIL(90) 이상이면 score 100`() {
        val result = service.build(pizzSignals(100, 100, 100), null, emptyList(), emptyList(), emptyPortfolio)
        val pizz = result.components.first { it.label == "PizzINT 종합" }
        assertEquals(100, pizz.score)
        assertEquals("지정학 노이즈 큼", pizz.state)
    }

    @Test
    fun `PizzINT baseline(평상시 60대) 은 중간값 이하로 recenter`() {
        // 평상시 PizzINT 가중 raw 가 60 안팎인 상황 → (60-48)/(90-48)*100 ≈ 28점.
        val result = service.build(pizzSignals(60, 60, 60), null, emptyList(), emptyList(), emptyPortfolio)
        val pizz = result.components.first { it.label == "PizzINT 종합" }
        assertTrue(pizz.score < 40, "score=${pizz.score} (baseline 이 중립 아래여야 함)")
    }

    // ─── 뉴스 컴포넌트 ──────────────────────────────────────────────────────
    @Test
    fun `강한 위험 키워드 다수면 뉴스 score 70 이상`() {
        val headlines = listOf(
            news("증시 폭락 쇼크"),
            news("경제 위기 침체 우려"),
            news("전쟁 패닉 분쟁 확산"),
            news("디폴트 위협 부도"),
        )
        val result = service.build(emptyList(), null, headlines, emptyList(), emptyPortfolio)
        val newsComp = result.components.first { it.label == "뉴스 키워드" }
        assertTrue(newsComp.score >= 70, "score=${newsComp.score}")
        assertEquals("위험 키워드 집중", newsComp.state)
    }

    @Test
    fun `위험 키워드 전혀 없으면 뉴스 score 낮음`() {
        val headlines = listOf(
            news("기업 신제품 출시"),
            news("배당 정책 발표"),
            news("M&A 거래 마무리"),
        )
        val result = service.build(emptyList(), null, headlines, emptyList(), emptyPortfolio)
        val newsComp = result.components.first { it.label == "뉴스 키워드" }
        assertEquals(0, newsComp.score)
        assertEquals("차분", newsComp.state)
    }

    // ─── 가중 합산 + 레벨 매핑 ──────────────────────────────────────────────
    @Test
    fun `모든 컴포넌트가 100 이면 최고 위험도(10·고위험)`() {
        val highVix = vix(50.0, 10.0)
        val highPizz = pizzSignals(100, 100, 100)
        val crashNews = (1..10).map { news("폭락 쇼크 패닉 위기") }

        val result = service.build(highPizz, highVix, crashNews, emptyList(), emptyPortfolio, krMarket(-10.0))
        assertEquals(10, result.score)
        assertEquals("고위험", result.level)
    }

    @Test
    fun `모든 컴포넌트가 안정이면 최저 위험도(1·안정)`() {
        val calmVix = vix(10.0, -1.0)
        val calmPizz = pizzSignals(0, 0, 0)
        val benignNews = listOf(news("기업 실적 호조"), news("거래량 평이"))

        val result = service.build(calmPizz, calmVix, benignNews, emptyList(), emptyPortfolio, krMarket(0.0))
        assertEquals(1, result.score)
        assertEquals("안정", result.level)
    }

    // ─── personalImpact 분기 ────────────────────────────────────────────────
    @Test
    fun `보유·관심 둘 다 없으면 personalImpact null`() {
        val result = service.build(emptyList(), null, emptyList(), emptyList(), emptyPortfolio)
        assertNull(result.personalImpact)
    }

    @Test
    fun `고위험 + 보유 종목 있으면 손절 점검 안내`() {
        val highVix = vix(40.0, 5.0)
        val result = service.build(pizzSignals(100, 100, 100), highVix,
            listOf(news("폭락 쇼크"), news("위기 패닉")), emptyList(), heldPortfolio())
        assertTrue(result.score >= 7, "score=${result.score}")
        assertNotNull(result.personalImpact)
        assertTrue(result.personalImpact!!.contains("손절"), "msg=${result.personalImpact}")
    }

    // ─── 시장별 합성 (KR / US) ───────────────────────────────────────────────
    @Test
    fun `buildKr 는 한국 지수·한국 뉴스만 반영하고 미국 신호는 무시`() {
        val krDanger = service.buildKr(
            koreaMarket = krMarket(-3.0, -2.0),
            news = listOf(news("코스피 사이드카 급락", "KR"), news("S&P 사상 최고치 랠리", "US")),
            watchlist = emptyList(), portfolio = emptyPortfolio,
        )
        assertEquals(2, krDanger.components.size)
        assertTrue(krDanger.components.any { it.label == "한국 지수 변동" })
        assertTrue(krDanger.components.none { it.label == "VIX 변동성" })
        assertTrue(krDanger.score >= 5, "score=${krDanger.score}")   // 한국 급락+위험뉴스 → 높음
        assertTrue(krDanger.headline.contains("한국"), "headline=${krDanger.headline}")
    }

    @Test
    fun `buildUs 는 美 VIX·미국 뉴스·PizzINT 로 산출하고 한국은 무시`() {
        val us = service.buildUs(
            vix = vix(14.0),
            alternativeSignals = pizzSignals(60, 60, 60),
            news = listOf(news("코스피 사이드카 급락", "KR"), news("뉴욕증시 최고치 마감", "US")),
            watchlist = emptyList(), portfolio = emptyPortfolio,
        )
        assertEquals(3, us.components.size)
        assertTrue(us.components.any { it.label == "VIX 변동성" })
        assertTrue(us.components.none { it.label == "한국 지수 변동" })
        // 한국 급락 뉴스는 US 합성에서 걸러져 위험도를 올리지 않는다 → 낮음
        assertTrue(us.score <= 4, "score=${us.score}")
        assertTrue(us.headline.contains("미국"), "headline=${us.headline}")
    }

    // ─── 메타 ───────────────────────────────────────────────────────────────
    @Test
    fun `headline 에 점수와 level 모두 포함`() {
        val result = service.build(emptyList(), null, emptyList(), emptyList(), emptyPortfolio)
        assertTrue(result.headline.contains("${result.score}/10"), "headline=${result.headline}")
        assertTrue(result.headline.contains(result.level), "headline=${result.headline}")
    }

    @Test
    fun `description·methodology 비어있지 않음`() {
        val result = service.build(emptyList(), null, emptyList(), emptyList(), emptyPortfolio)
        assertFalse(result.description.isBlank())
        assertFalse(result.methodology.isBlank())
    }
}
