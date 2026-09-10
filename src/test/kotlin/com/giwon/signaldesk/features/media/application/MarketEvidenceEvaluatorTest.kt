package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.common.KST
import com.giwon.signaldesk.features.market.application.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MarketEvidenceEvaluatorTest {
    private val sessions = MarketSessionService()
    private val analyzer = MarketEvidenceAnalyzer(sessions)
    private val evaluator = MarketEvidenceEvaluator(analyzer, sessions)
    private val date = LocalDate.parse("2026-09-10")
    private val now = date.atTime(17, 0).atZone(KST).toInstant()
    private val from = now.minus(Duration.ofDays(90))

    private fun input(date: LocalDate = this.date, hour: Int = 8): MarketEvidenceInput {
        val at = date.atTime(hour, 30).atZone(KST).toInstant()
        val quotes = YahooQuoteClient.BRIEFING_INDICES.keys.map { id ->
            val market = when (id) { "^KS11", "^KQ11" -> "KR"; "SMSN.IL" -> "UK"; "ES=F", "CL=F" -> "FUTURES"; "KRW=X" -> "FX"; else -> "US" }
            val zone = ZoneId.of(when (market) { "KR" -> "Asia/Seoul"; "UK" -> "Europe/London"; else -> "America/New_York" })
            val isContinuous = market in setOf("FUTURES", "FX")
            val observation = if (isContinuous) at.atZone(zone).toLocalDate() else analyzer.expectedSession(at, market)
            val quoteTime = if (isContinuous) at.minusSeconds(60) else observation.atTime(15, 0).atZone(zone).toInstant()
            val rate = when(id) { "KRW=X" -> -.7; "CL=F", "^VIX" -> 0.0; else -> 2.0 }
            val value = when(id) { "^VIX" -> 18.0; "CL=F" -> 75.0; else -> 100.0 }
            GlobalIndex(id, value, rate, id, quoteTime.toString(), "Yahoo:$id",
                value / (1 + rate / 100), observation.toString(), observation.minusDays(1).toString())
        }
        return MarketEvidenceInput(at, quotes, null,
            listOf(MarketNews("GLOBAL", "기업 실적 발표 대기", "Test source", "https://example.com/news", "", at.minusSeconds(60).toString())))
    }
    private fun archived(input: MarketEvidenceInput = input()): ArchivedMarketEvidence {
        val report = analyzer.analyze(input)
        return ArchivedMarketEvidence(input.collectedAt.truncatedTo(ChronoUnit.HOURS), report.rulesVersion, input.collectedAt, input, report)
    }
    private fun candle(date: LocalDate, open: Double = 101.0) = IndexCandle(date.format(DateTimeFormatter.BASIC_ISO_DATE), open, 102.0, 99.0, 100.0, 1000)
    private fun candles() = listOf(candle(date.minusDays(1), 100.0), candle(date))
    private fun evaluate(rows: List<ArchivedMarketEvidence>, bars: List<IndexCandle> = candles(), at: Instant = now) =
        evaluator.evaluate(rows, bars, from, at)

    @Test fun `empty history is explicitly uncalibrated without fabricated accuracy`() {
        val r = evaluate(emptyList())
        assertThat(r.replay.snapshotCount).isZero()
        assertThat(r.exploratory.status).isEqualTo("INSUFFICIENT_HISTORY")
        assertThat(r.exploratory.matchPercent).isNull()
        assertThat(r.exploratory.missingPreopenDays).isEqualTo(r.exploratory.expectedTradingDays).isPositive()
        assertThat(r.calibrationStatus).isEqualTo("NOT_CALIBRATED")
        assertThat(r.parametersChanged).isFalse()
    }

    @Test fun `archive JSON roundtrip replays identically without current quotes`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val row = archived()
        val replayInput = mapper.readValue(mapper.writeValueAsString(row.input), MarketEvidenceInput::class.java)
        val replayReport = mapper.readValue(mapper.writeValueAsString(row.report), MarketEvidenceReport::class.java)
        val r = evaluate(listOf(row.copy(input = replayInput, report = replayReport)))
        assertThat(r.replay.sameVersionCount).isEqualTo(1)
        assertThat(r.replay.sameVersionDriftCount).isZero()
        assertThat(r.exploratory.directionalDays).isEqualTo(1)
        assertThat(r.exploratory.matchedDays).isEqualTo(1)
        assertThat(r.exploratory.matchPercent).isNull()
    }

    @Test fun `same version mutation is drift but version change is not nondeterminism`() {
        val row = archived()
        val changed = row.copy(report = row.report!!.copy(regime = "PRESSURED"))
        assertThat(evaluate(listOf(changed)).replay.sameVersionDriftCount).isEqualTo(1)
        val old = changed.copy(rulesVersion = "old-v1", report = changed.report!!.copy(rulesVersion = "old-v1"))
        val r = evaluate(listOf(old))
        assertThat(r.replay.sameVersionDriftCount).isZero()
        assertThat(r.replay.changedDecisionCount).isEqualTo(1)
    }

    @Test fun `truncated input does not present a selectively bounded success rate`() {
        val r = evaluate(List(2161) { archived() })
        assertThat(r.replay.truncated).isTrue()
        assertThat(r.exploratory.status).isEqualTo("TRUNCATED_HISTORY")
        assertThat(r.exploratory.matchPercent).isNull()
    }

    @Test fun `corrupt mismatched future and duplicate archive rows cannot become samples`() {
        val row = archived()
        val r = evaluate(listOf(row, row, row.copy(input = null), row.copy(report = null),
            row.copy(observedAt = now.plusSeconds(1)), row.copy(bucketAt = now), row.copy(rulesVersion = "wrong")))
        assertThat(r.replay.snapshotCount).isEqualTo(1)
        assertThat(r.replay.rejectedCount).isEqualTo(6)
    }

    @Test fun `last preopen snapshot wins before checking quality and postopen input is excluded`() {
        val early = archived(input(hour = 7))
        val late = archived(input().copy(quotes = emptyList()))
        val postopen = archived(input(hour = 10))
        val r = evaluate(listOf(early, late, postopen))
        assertThat(r.exploratory.preopenDays).isEqualTo(1)
        assertThat(r.exploratory.abstainedDays).isEqualTo(1)
        assertThat(r.exploratory.directionalDays).isZero()
    }

    @Test fun `multiple versions and hours on one day never inflate outcome sample count`() {
        val row = archived()
        val old = row.copy(rulesVersion = "v1", report = row.report!!.copy(rulesVersion = "v1"))
        assertThat(evaluate(listOf(row, old, archived(input(hour = 7)))).exploratory.directionalDays).isEqualTo(1)
    }

    @Test fun `partial day missing previous session duplicate and nonfinite candles are not outcome labels`() {
        val row = archived()
        assertThat(evaluate(listOf(row), at = date.atTime(15, 59).atZone(KST).toInstant()).exploratory.unavailableOutcomeDays).isEqualTo(1)
        for (bars in listOf(candles().drop(1), candles() + candle(date), listOf(candle(date.minusDays(2)), candle(date)),
            listOf(candle(date.minusDays(1)), candle(date).copy(open = Double.NaN)),
            listOf(candle(date.minusDays(1)).copy(open = Double.MIN_VALUE, close = Double.MIN_VALUE, low = Double.MIN_VALUE), candle(date)),
            listOf(candle(date.minusDays(1)), candle(date).copy(high = 1.0)))) {
            assertThat(evaluate(listOf(row), bars).exploratory.unavailableOutcomeDays).isEqualTo(1)
        }
    }

    @Test fun `holiday and observations outside preopen window are not counted`() {
        val holiday = LocalDate.parse("2026-08-17")
        val r = evaluate(listOf(archived(input(holiday)), archived(input(hour = 6)), archived(input(hour = 9))))
        assertThat(r.exploratory.preopenDays).isEqualTo(1) // 06:30 is inclusive; 09:30 and holiday excluded
    }

    @Test fun `thirty independent dates unlock only exploratory rate interval and baseline`() {
        val dates = generateSequence(date.minusDays(60)) { it.plusDays(1) }.takeWhile { it <= date }
            .filter { sessions.isKrTradingDay(it) }.takeLastList(30)
        val rows = dates.map { archived(input(it)) }
        val bars = generateSequence(dates.first().minusDays(7)) { it.plusDays(1) }.takeWhile { it <= date }
            .filter { sessions.isKrTradingDay(it) }.map { candle(it) }.toList()
        val r = evaluate(rows, bars)
        assertThat(r.exploratory.directionalDays).isEqualTo(30)
        assertThat(r.exploratory.matchPercent).isEqualTo(100.0)
        assertThat(r.exploratory.alwaysUpMatchPercent).isEqualTo(100.0)
        assertThat(r.exploratory.interval95LowPercent).isBetween(80.0, 100.0)
        assertThat(r.exploratory.status).isEqualTo("EXPLORATORY_ONLY")
        assertThat(r.calibrationStatus).isEqualTo("NOT_CALIBRATED")
    }

    private fun <T> Sequence<T>.takeLastList(n: Int) = toList().takeLast(n)
}
