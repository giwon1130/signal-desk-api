package com.giwon.signaldesk.features.push.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class WatchlistAlertMessageTest {
    private inline fun <reified T> stub(): T = mock(T::class.java)
    private val service = WatchlistAlertService(
        jdbcTemplate = stub(), pushRepository = stub(), alertPreferenceService = stub(),
        quoteClient = stub(), globalQuoteClient = stub(), yahooFinanceScreenerClient = stub(),
        chartClient = stub(), technicalCalculator = stub(), detector = WatchlistAlertDetector(),
        expoPushClient = stub(), moverReasonService = stub(),
    )

    @Test fun `both rising and falling alerts lead with price and do not invent a missing cause`() {
        for ((direction, change) in listOf(AlertDirection.UP to 5.2, AlertDirection.DOWN to -6.1)) {
            val candidate = AlertCandidate(UUID.randomUUID(), "005930", "삼성전자", "KR", change, direction, 75000.0)
            val message = service.buildMessage("test-device", candidate)
            assertThat(message.title).contains("삼성전자")
            assertThat(message.body).isEqualTo("75,000원 · 가격 변동이 커졌습니다. 최신 공시와 뉴스를 함께 확인해 주세요.")
                .doesNotContain("수급", "모멘텀", "추정", "익절", "손절")
        }
    }
    @Test fun `verified news is delivered without an unknown cause prefix and USD is not KRW`() {
        val candidate = AlertCandidate(UUID.randomUUID(), "AAPL", "Apple", "US", 5.2, AlertDirection.UP, 200.0)
        val body = service.buildMessage("test", candidate, "관련 보도: 실적 발표").body
        assertThat(body).isEqualTo("\$200.00 · 관련 보도: 실적 발표").doesNotContain("원인", "원 ·")
    }
    @Test fun `long headline denial is not cut off in a short push`() {
        val candidate = AlertCandidate(UUID.randomUUID(), "005930", "삼성전자", "KR", 5.2, AlertDirection.UP, 75000.0)
        val body = service.buildMessage("test", candidate, "관련 보도: " + "인수 계약 관련 보도 ".repeat(20) + "사실 아냐").body
        assertThat(body).contains("종목 상세").doesNotContain("인수 계약")
    }
}
