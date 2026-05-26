package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.bootstrap.SignalDeskApiApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    classes = [SignalDeskApiApplication::class],
    properties = [
        "signal-desk.integrations.krx.enabled=false",
        "signal-desk.integrations.naver.enabled=false",
        "signal-desk.integrations.cboe.enabled=false",
        "signal-desk.integrations.fred.enabled=false",
        "signal-desk.integrations.google-news.enabled=false",
        "signal-desk.integrations.pizzint.enabled=false",
        "signal-desk.integrations.naver-global.enabled=false",
        "signal-desk.integrations.yahoo-screener.enabled=false",
    ]
)
@AutoConfigureMockMvc
class MarketOverviewControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `시장 요약을 반환한다`() {
        mockMvc.get("/api/v1/market/summary")
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                // Fear Meter / KR Heat / US Heat / Flow Bias 4종은 항상 생성된다.
                jsonPath("$.data.marketSummary.length()") { value(4) }
                // 합성 위험도: VIX / PizzINT / 뉴스 3개 컴포넌트 + 1~10 점수.
                jsonPath("$.data.compositeRisk.components.length()") { value(3) }
                jsonPath("$.data.compositeRisk.score") { exists() }
            }
    }

    @Test
    fun `시장 섹션을 반환한다`() {
        mockMvc.get("/api/v1/market/sections")
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.data.koreaMarket.market") { value("KR") }
                jsonPath("$.data.usMarket.market") { value("US") }
                // 외부 연동을 모두 끈 테스트 환경 — 가짜 fallback 없이 지수는 빈 리스트.
                jsonPath("$.data.usMarket.indices.length()") { value(0) }
            }
    }
}
