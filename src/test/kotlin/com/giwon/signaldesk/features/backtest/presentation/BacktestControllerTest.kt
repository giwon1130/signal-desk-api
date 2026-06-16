package com.giwon.signaldesk.features.backtest.presentation

import com.giwon.signaldesk.bootstrap.SignalDeskApiApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 백테스트 엔드포인트가 캐시 프록시(@Cacheable)를 거쳐 정상 응답하는지 — 어노테이션 속성
 * 조합 오류(예: sync=true + unless 병행 불가)는 호출 시점에야 터져서 단위 테스트로는 안 잡힌다.
 * 외부 연동(야후)을 꺼서 "데이터 없음 → success=false, 200" 경로를 검증한다.
 */
@SpringBootTest(
    classes = [SignalDeskApiApplication::class],
    properties = [
        "signal-desk.integrations.krx.enabled=false",
        "signal-desk.integrations.naver.enabled=false",
        "signal-desk.integrations.cboe.enabled=false",
        "signal-desk.integrations.fred.enabled=false",
        "signal-desk.integrations.google-news.enabled=false",
        "signal-desk.integrations.naver-global.enabled=false",
        "signal-desk.integrations.yahoo-screener.enabled=false",
        "signal-desk.integrations.yahoo-quote.enabled=false",
        "signal-desk.integrations.sec-edgar.enabled=false",
        "signal-desk.store.mode=file",
    ]
)
@AutoConfigureMockMvc
class BacktestControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `시즈널리티 — 데이터 없어도 500이 아니라 200 + success=false`() {
        mockMvc.get("/api/v1/backtest/seasonality?market=US&ticker=TESTONLY")
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(false) }
            }
    }

    @Test
    fun `섹터 로테이션 — 데이터 없어도 500이 아니라 200 + success=false`() {
        mockMvc.get("/api/v1/backtest/sector-rotation?market=US")
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(false) }
            }
    }
}
