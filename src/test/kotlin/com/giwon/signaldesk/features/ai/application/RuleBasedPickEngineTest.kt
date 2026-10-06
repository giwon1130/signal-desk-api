package com.giwon.signaldesk.features.ai.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RuleBasedPickEngineTest {
    private val end = LocalDate.parse("2026-10-05")
    private val candidate = PickCandidate("US", "TEST", "Test", 105.0, 1.0, null)
    private fun bars() = (0..20).map { PickDailyObservation(end.minusDays((20-it).toLong()), 100.0 + it * .2, if (it == 20) 2_000_000 else 1_000_000) }
    private fun assess(rows: List<PickDailyObservation>) = RuleBasedPickEngine.assess(candidate, rows, end)

    @Test fun `trend volume and liquidity pass deterministic review without probability`() {
        val result = assess(bars())
        assertThat(result.decision).isEqualTo(PickDecision.REVIEW)
        assertThat(result.metrics!!.completedVolumeRatio).isEqualTo(2.0)
        assertThat(result.analysisDate).isEqualTo("2026-10-05")
        assertThat(result).isEqualTo(assess(bars().reversed()))
    }
    @Test fun `missing stale duplicate and zero volume fail closed`() {
        listOf(bars().dropLast(1), bars().drop(1), bars()+bars().last(), bars().map { it.copy(volume = 0) })
            .forEach { assertThat(assess(it).decision).isEqualTo(PickDecision.INSUFFICIENT_DATA) }
    }
    @Test fun `incomplete future session never changes the completed daily signal`() {
        val future = PickDailyObservation(end.plusDays(1), 5000.0, Long.MAX_VALUE)
        assertThat(assess(bars()+future)).isEqualTo(assess(bars()))
    }
    @Test fun `low liquidity weak volume and falling trend cannot produce review`() {
        val samples = listOf(bars().map { it.copy(volume = 100) }, bars().dropLast(1)+bars().last().copy(volume = 500000),
            bars().mapIndexed { index, b -> b.copy(close = 110.0-index*.2) })
        samples.forEach { assertThat(assess(it).decision).isEqualTo(PickDecision.WATCH) }
    }
    @Test fun `NaN invalid prices split sized jumps and old sparse history do not pass`() {
        val samples = listOf(bars().dropLast(1)+bars().last().copy(close = Double.NaN),
            bars().dropLast(1)+bars().last().copy(close = 0.0),
            bars().dropLast(1)+bars().last().copy(close = 50.0),
            bars().mapIndexed { i, b -> if(i==0) b.copy(date=end.minusDays(100)) else b })
        samples.forEach { assertThat(assess(it).decision).isEqualTo(PickDecision.INSUFFICIENT_DATA) }
    }
    @Test fun `US and KR turnover thresholds respect their own currency`() {
        val rows=bars().map { it.copy(close=it.close*1000, volume=10000) }
        assertThat(RuleBasedPickEngine.assess(candidate.copy(market="KR"),rows,end).blockers).noneMatch { it.contains("유동성") }
        assertThat(RuleBasedPickEngine.assess(candidate.copy(market="EU"),rows,end).decision).isEqualTo(PickDecision.INSUFFICIENT_DATA)
    }
}
