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
    ]
)
@AutoConfigureMockMvc
class MarketOverviewControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `시장 개요를 반환한다`() {
        mockMvc.get("/api/v1/market/overview")
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.data.marketSummary.length()") { value(4) }
                jsonPath("$.data.watchlist.length()") { value(4) }
                jsonPath("$.data.koreaMarket.indices.length()") { value(2) }
                jsonPath("$.data.aiRecommendations.picks.length()") { value(3) }
            }
    }
}
