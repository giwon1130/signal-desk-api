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
        // US 지수 1순위 소스(야후 v8 chart)도 꺼야 "외부 연동 전부 OFF → 지수 빈 리스트" 전제가 성립.
        "signal-desk.integrations.yahoo-quote.enabled=false",
        "signal-desk.integrations.sec-edgar.enabled=false",
        "signal-desk.store.mode=file",
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
                // Missing feeds must not produce neutral scores or simulated indicators.
                jsonPath("$.data.marketSummary.length()") { value(0) }
                // Compatibility fields remain, but uncalibrated numeric risk is absent.
                jsonPath("$.data.compositeRisk.components.length()") { value(0) }
                jsonPath("$.data.compositeRisk.score") { doesNotExist() }
                jsonPath("$.data.compositeRisk.riskLevel") { value("UNKNOWN") }
                jsonPath("$.data.marketConditions[0].direction") { value("UNKNOWN") }
                jsonPath("$.data.marketConditions[0].dataStatus") { value("INSUFFICIENT") }
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
