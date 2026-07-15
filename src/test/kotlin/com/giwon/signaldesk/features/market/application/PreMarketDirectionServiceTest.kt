package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * 야간 방향성 bias 룰 회귀 보호 — MSCI한국 0.35 + 런던삼성 0.25 + 하이닉스ADR 0.20 + 마이크론 0.10 + S&P선물 0.10 가중, 임계 ±0.3%.
 * 핵심 지표 가중치가 55% 미만이면 방향을 제시하지 않는다.
 */
class PreMarketDirectionServiceTest {

    private val service = PreMarketDirectionService(
        yahooQuoteClient = YahooQuoteClient(ObjectMapper(), enabled = false, baseUrl = "http://unused", httpFetchExecutor = Executors.newSingleThreadExecutor()),
        marketSessionService = MarketSessionService(),
        biasThreshold = 0.3,
    )

    @Test
    fun `다섯 지표가 모두 강한 상승이면 RISING`() {
        assertThat(service.computeSignal(gaugeRate = 1.0, londonRate = 1.0, hynixAdrRate = 1.0, micronRate = 1.0, spRate = 1.0).bias).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `다섯 지표가 모두 하락이면 FALLING`() {
        assertThat(service.computeSignal(gaugeRate = -1.0, londonRate = -0.5, hynixAdrRate = -0.6, micronRate = -0.7, spRate = -0.8).bias).isEqualTo(PreMarketDirectionService.Bias.FALLING)
    }

    @Test
    fun `상승·하락이 상쇄되면 NEUTRAL`() {
        // (0.35*0.3 + 0.25*(-0.3) + 0.20*0.0 + 0.10*0.0 + 0.10*0.0)/1.0 = 0.03 → ±0.3 안 → NEUTRAL
        assertThat(service.computeSignal(gaugeRate = 0.3, londonRate = -0.3, hynixAdrRate = 0.0, micronRate = 0.0, spRate = 0.0).bias).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }

    @Test
    fun `가중합이 임계값과 같으면 RISING(경계 포함)`() {
        // 다섯 지표가 모두 0.3 → 정규화 가중평균 0.3 == threshold → RISING
        assertThat(service.computeSignal(gaugeRate = 0.3, londonRate = 0.3, hynixAdrRate = 0.3, micronRate = 0.3, spRate = 0.3).bias).isEqualTo(PreMarketDirectionService.Bias.RISING)
    }

    @Test
    fun `마이크론만 있으면 자료 부족으로 처리한다`() {
        assertThat(service.computeSignal(gaugeRate = null, londonRate = null, hynixAdrRate = null, micronRate = 0.5, spRate = null).bias).isNull()
        assertThat(service.computeSignal(gaugeRate = null, londonRate = null, hynixAdrRate = null, micronRate = -0.4, spRate = null).confidence).isEqualTo(PreMarketDirectionService.Confidence.INSUFFICIENT)
    }

    @Test
    fun `하이닉스 ADR만 있으면 자료 부족으로 처리한다`() {
        assertThat(service.computeSignal(gaugeRate = null, londonRate = null, hynixAdrRate = 0.5, micronRate = null, spRate = null).bias).isNull()
        assertThat(service.computeSignal(gaugeRate = null, londonRate = null, hynixAdrRate = -0.4, micronRate = null, spRate = null).confidence).isEqualTo(PreMarketDirectionService.Confidence.INSUFFICIENT)
    }

    @Test
    fun `MSCI한국만 있으면 그 값으로 정규화 판정`() {
        assertThat(service.computeSignal(gaugeRate = 0.5, londonRate = null, hynixAdrRate = null, micronRate = null, spRate = null).bias).isNull()
    }

    @Test
    fun `핵심 가중치가 55퍼센트 미만이면 방향을 제시하지 않는다`() {
        val signal = service.computeSignal(gaugeRate = null, londonRate = null, hynixAdrRate = 0.5, micronRate = 0.5, spRate = 0.5)
        assertThat(signal.bias).isNull()
        assertThat(signal.coverage).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.000_001))
    }

    @Test
    fun `한국 거래일 장전 시간에만 예측을 노출한다`() {
        val zone = ZoneId.of("Asia/Seoul")
        assertThat(service.isPredictionWindow(ZonedDateTime.of(2026, 7, 15, 6, 30, 0, 0, zone))).isTrue()
        assertThat(service.isPredictionWindow(ZonedDateTime.of(2026, 7, 15, 9, 0, 0, 0, zone))).isFalse()
        assertThat(service.isPredictionWindow(ZonedDateTime.of(2026, 7, 18, 8, 0, 0, 0, zone))).isFalse()
    }
}
