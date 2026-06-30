package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * 야간 방향성 bias 룰 회귀 보호 — MSCI한국 0.45 + 런던삼성 0.35 + S&P선물 0.20 가중, 임계 ±0.3%.
 * 결측 지표는 빼고 남은 가중치로 정규화, 전부 없으면 NEUTRAL.
 */
class PreMarketDirectionServiceTest {

    private val service = PreMarketDirectionService(
        yahooQuoteClient = YahooQuoteClient(ObjectMapper(), enabled = false, baseUrl = "http://unused", httpFetchExecutor = Executors.newSingleThreadExecutor()),
        biasThreshold = 0.3,
    )

    @Test
    fun `셋 다 강한 상승이면 RISING`() {
        assertThat(service.computeBias(gaugeRate = 1.0, londonRate = 1.0, spRate = 1.0)).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `셋 다 하락이면 FALLING`() {
        assertThat(service.computeBias(gaugeRate = -1.0, londonRate = -0.5, spRate = -0.8)).isEqualTo(PreMarketDirectionService.Bias.FALLING)
    }

    @Test
    fun `상승·하락이 상쇄되면 NEUTRAL`() {
        // (0.45*0.3 + 0.35*(-0.3) + 0.20*0.0)/1.0 = 0.03 → ±0.3 안 → NEUTRAL
        assertThat(service.computeBias(gaugeRate = 0.3, londonRate = -0.3, spRate = 0.0)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }

    @Test
    fun `가중합이 임계값과 같으면 RISING(경계 포함)`() {
        // 셋 다 0.3 → 정규화 가중평균 0.3 == threshold → RISING
        assertThat(service.computeBias(gaugeRate = 0.3, londonRate = 0.3, spRate = 0.3)).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `MSCI한국만 있으면 그 값으로 정규화 판정`() {
        // 결측은 빼고 남은 가중(0.45)으로 정규화 → 게이지 값 그대로
        assertThat(service.computeBias(gaugeRate = 0.5, londonRate = null, spRate = null)).isEqualTo(PreMarketDirectionService.Bias.RISING)
        assertThat(service.computeBias(gaugeRate = -0.4, londonRate = null, spRate = null)).isEqualTo(PreMarketDirectionService.Bias.FALLING)
    }

    @Test
    fun `런던삼성만 있으면 그 값으로 정규화 판정`() {
        assertThat(service.computeBias(gaugeRate = null, londonRate = 0.5, spRate = null)).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `전부 없으면 NEUTRAL`() {
        assertThat(service.computeBias(gaugeRate = null, londonRate = null, spRate = null)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }
}
