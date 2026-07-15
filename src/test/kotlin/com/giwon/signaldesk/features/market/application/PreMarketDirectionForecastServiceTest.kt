package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PreMarketDirectionForecastServiceTest {

    @Test
    fun `시초 갭이 0점05퍼센트 이상이면 상승으로 분류한다`() {
        assertThat(PreMarketDirectionForecastService.biasOf(0.05)).isEqualTo(PreMarketDirectionService.Bias.RISING)
        assertThat(PreMarketDirectionForecastService.biasOf(0.04)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }

    @Test
    fun `시초 갭이 마이너스 0점05퍼센트 이하이면 하락으로 분류한다`() {
        assertThat(PreMarketDirectionForecastService.biasOf(-0.05)).isEqualTo(PreMarketDirectionService.Bias.FALLING)
        assertThat(PreMarketDirectionForecastService.biasOf(-0.04)).isEqualTo(PreMarketDirectionService.Bias.NEUTRAL)
    }
}
