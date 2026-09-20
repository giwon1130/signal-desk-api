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

    @Test fun `both rising and falling alerts explicitly disclose missing cause`() {
        for ((direction, change) in listOf(AlertDirection.UP to 5.2, AlertDirection.DOWN to -6.1)) {
            val candidate = AlertCandidate(UUID.randomUUID(), "005930", "삼성전자", "KR", change, direction, 75000)
            val message = service.buildMessage("test-device", candidate)
            assertThat(message.title).contains("삼성전자")
            assertThat(message.body).isEqualTo("75,000원 · 주가 변동의 원인은 확인되지 않았습니다.")
                .doesNotContain("수급", "모멘텀", "추정", "익절", "손절")
        }
    }
}
