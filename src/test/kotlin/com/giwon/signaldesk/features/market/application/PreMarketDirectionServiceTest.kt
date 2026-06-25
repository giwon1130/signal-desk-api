package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * 야간 방향성 bias 룰 회귀 보호 — 선물 0.6 + 런던삼성 0.4 가중, 임계 ±0.3%.
 * 한쪽만 있으면 그쪽 100%, 둘 다 없으면 NEUTRAL.
 */
class PreMarketDirectionServiceTest {

    private val service = PreMarketDirectionService(
        naverIndexQuoteClient = NaverIndexQuoteClient(ObjectMapper(), enabled = false),
        yahooQuoteClient = YahooQuoteClient(ObjectMapper(), enabled = false, baseUrl = "http://unused", httpFetchExecutor = Executors.newSingleThreadExecutor()),
        futuresCode = "FUT",
        biasThreshold = 0.3,
    )

    @Test
    fun `둘 다 강한 상승이면 RISING`() {
        assertThat(service.computeBias(futuresRate = 1.0, londonRate = 1.0)).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `둘 다 하락이면 FALLING`() {
        assertThat(service.computeBias(futuresRate = -1.0, londonRate = -0.5)).isEqualTo(PreMarketDirectionService.Bias.FALLING)
    }

    @Test
    fun `선물 약상승과 런던 하락이 상쇄되면 NEUTRAL`() {
        // 0.6*0.5 + 0.4*(-1.0) = -0.1 → ±0.3 안 → NEUTRAL
        assertThat(service.computeBias(futuresRate = 0.5, londonRate = -1.0)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }

    @Test
    fun `가중합이 임계값과 같으면 RISING(경계 포함)`() {
        // 0.6*0.5 + 0.4*0.0 = 0.3 == threshold → RISING
        assertThat(service.computeBias(futuresRate = 0.5, londonRate = 0.0)).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `선물만 있으면 그 값 100퍼센트로 판정`() {
        assertThat(service.computeBias(futuresRate = 0.5, londonRate = null)).isEqualTo(PreMarketDirectionService.Bias.RISING)
        assertThat(service.computeBias(futuresRate = -0.4, londonRate = null)).isEqualTo(PreMarketDirectionService.Bias.FALLING)
    }

    @Test
    fun `런던만 있으면 그 값 100퍼센트로 판정`() {
        assertThat(service.computeBias(futuresRate = null, londonRate = 0.5)).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `둘 다 없으면 NEUTRAL`() {
        assertThat(service.computeBias(futuresRate = null, londonRate = null)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }
}
