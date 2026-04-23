package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndexChartFactoryTest {

    @Test
    fun `OHLC 캔들이 들어오면 시뮬레이션 없이 그대로 ChartPoint로 변환된다`() {
        val daily = listOf(
            IndexCandle("20260420", open = 2700.0, high = 2710.0, low = 2685.0, close = 2705.0, volume = 1_000_000),
            IndexCandle("20260421", open = 2705.0, high = 2725.0, low = 2700.0, close = 2720.0, volume = 1_200_000),
            IndexCandle("20260422", open = 2720.0, high = 2740.0, low = 2715.0, close = 2735.0, volume = 1_500_000),
        )

        val periods = buildIndexChartPeriodsFromOhlc(
            latest = 2740.0,
            changeRate = 0.18,
            dailyCandles = daily,
            weeklyCandles = emptyList(),   // 폴백 시뮬레이션으로 가야 함
            monthlyCandles = emptyList(),
        )

        val dailySnap = periods.first { it.key == "D" }
        assertThat(dailySnap.points).hasSize(3)
        // 첫 두 캔들은 원본 그대로
        assertThat(dailySnap.points[0].open).isEqualTo(2700.0)
        assertThat(dailySnap.points[0].close).isEqualTo(2705.0)
        assertThat(dailySnap.points[1].high).isEqualTo(2725.0)
        // 마지막 캔들의 close는 latest(2740.0)로 보정
        assertThat(dailySnap.points[2].close).isEqualTo(2740.0)
        // latest > high 였으므로 high도 latest로 확장
        assertThat(dailySnap.points[2].high).isEqualTo(2740.0)
        assertThat(dailySnap.points[2].volume).isEqualTo(1_500_000)
        // 라벨은 MM/dd 포맷
        assertThat(dailySnap.points[0].label).isEqualTo("04/20")
    }

    @Test
    fun `OHLC가 비어있으면 시뮬레이션으로 폴백한다`() {
        val periods = buildIndexChartPeriodsFromOhlc(
            latest = 3000.0,
            changeRate = 0.5,
            dailyCandles = emptyList(),
            weeklyCandles = emptyList(),
            monthlyCandles = emptyList(),
        )

        val dailySnap = periods.first { it.key == "D" }
        assertThat(dailySnap.points).hasSize(30)
        // 마지막 close는 latest 근처 (시뮬레이션도 마지막은 latest)
        assertThat(dailySnap.points.last().close).isEqualTo(3000.0)
    }

    @Test
    fun `30개 초과 캔들이 들어오면 마지막 30개만 사용한다`() {
        val many = (1..50).map { i ->
            IndexCandle("2026010${i.toString().padStart(2, '0')}", 100.0 + i, 110.0 + i, 95.0 + i, 105.0 + i, 1000)
        }

        val periods = buildIndexChartPeriodsFromOhlc(
            latest = 155.0,
            changeRate = 0.0,
            dailyCandles = many,
            weeklyCandles = emptyList(),
            monthlyCandles = emptyList(),
        )

        val dailySnap = periods.first { it.key == "D" }
        assertThat(dailySnap.points).hasSize(30)
        // takeLast(30): 첫 캔들의 raw close는 105.0 + 21 = 126.0
        assertThat(dailySnap.points[0].close).isEqualTo(126.0)
    }
}
