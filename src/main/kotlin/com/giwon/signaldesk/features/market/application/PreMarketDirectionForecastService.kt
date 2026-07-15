package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.common.KST
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 장전 방향성의 입력·예측·실제 KOSPI 시초 갭을 날짜 단위로 보존한다.
 *
 * 08:50 KST에 예측을 기록하고, 09:10 KST에 Naver 일봉의 시초가가 확인되면 평가한다.
 * 신호가 부족해 방향을 제시하지 않은 날도 저장해 데이터 가용성 자체를 측정할 수 있게 한다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class PreMarketDirectionForecastService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val directionService: PreMarketDirectionService,
    private val marketSessionService: MarketSessionService,
    private val naverIndexChartClient: NaverIndexChartClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class CaptureResult(val saved: Boolean, val bias: String?, val coverage: Int?)
    data class EvaluationResult(val evaluated: Boolean, val actualGapRate: Double?, val correct: Boolean?)

    /** 최근 평가 완료된 방향 예측만 모아 적중률을 계산한다. 방향을 내지 못한 날은 표본에서 제외한다. */
    fun stats(windowSize: Int = DEFAULT_STATS_WINDOW): PreMarketForecastStats {
        val rows = jdbc.query(
            """
            select prediction_date, correct, actual_gap_rate
            from signal_desk_premarket_direction_forecast
            where evaluated_at is not null and correct is not null
            order by prediction_date desc
            limit ?
            """.trimIndent(),
            { rs, _ ->
                ForecastRow(
                    predictionDate = rs.getDate("prediction_date").toLocalDate(),
                    correct = rs.getBoolean("correct"),
                    actualGapRate = rs.getDouble("actual_gap_rate").takeUnless { rs.wasNull() },
                )
            },
            windowSize.coerceAtLeast(1),
        )
        val last = rows.firstOrNull()
        val correctCount = rows.count { it.correct }
        return PreMarketForecastStats(
            evaluatedCount = rows.size,
            correctCount = correctCount,
            accuracyPct = rows.takeIf { it.isNotEmpty() }?.let { correctCount * 100 / it.size },
            windowSize = windowSize,
            lastPredictionDate = last?.predictionDate?.toString(),
            lastCorrect = last?.correct,
            lastActualGapRate = last?.actualGapRate,
        )
    }

    fun capture(date: LocalDate = LocalDate.now(KST)): CaptureResult {
        if (!marketSessionService.isKrTradingDay(date)) return CaptureResult(false, null, null)
        val direction = directionService.current()
        val inputs = objectMapper.writeValueAsString(
            (listOfNotNull(direction.kospiFutures) + direction.overseas)
                .map { mapOf("label" to it.label, "changeRate" to it.changeRate, "value" to it.value) },
        )
        jdbc.update(
            """
            insert into signal_desk_premarket_direction_forecast
                (prediction_date, recorded_at, bias, score, confidence, coverage, input_count, inputs)
            values (?, now(), ?, ?, ?, ?, ?, ?::jsonb)
            on conflict (prediction_date) do update set
                recorded_at = excluded.recorded_at, bias = excluded.bias, score = excluded.score,
                confidence = excluded.confidence, coverage = excluded.coverage,
                input_count = excluded.input_count, inputs = excluded.inputs
            """.trimIndent(),
            java.sql.Date.valueOf(date), direction.bias, direction.score, direction.confidence,
            direction.coverage, direction.inputCount, inputs,
        )
        log.info("premarket forecast captured — date={} bias={} coverage={}", date, direction.bias, direction.coverage)
        return CaptureResult(true, direction.bias, direction.coverage)
    }

    fun evaluate(date: LocalDate = LocalDate.now(KST)): EvaluationResult {
        if (!marketSessionService.isKrTradingDay(date)) return EvaluationResult(false, null, null)
        val candles = runCatching {
            naverIndexChartClient.fetchOhlc("KOSPI", NaverIndexChartClient.PeriodType.DAILY, 10)
        }.onFailure { log.warn("premarket forecast KOSPI candle fetch failed", it) }.getOrDefault(emptyList())
            .sortedBy { it.date }
        val todayKey = date.toString().replace("-", "")
        val todayIndex = candles.indexOfFirst { it.date == todayKey }
        val today = candles.getOrNull(todayIndex) ?: return EvaluationResult(false, null, null)
        val previous = candles.getOrNull(todayIndex - 1)?.close ?: return EvaluationResult(false, null, null)
        if (previous <= 0.0 || today.open <= 0.0) return EvaluationResult(false, null, null)

        val gapRate = (today.open - previous) / previous * 100
        val actualBias = biasOf(gapRate)
        val forecastBias = jdbc.query(
            "select bias from signal_desk_premarket_direction_forecast where prediction_date = ?",
            { rs, _ -> rs.getString("bias") }, java.sql.Date.valueOf(date),
        ).firstOrNull()
        val correct = forecastBias?.let { it == actualBias.name }
        val updated = jdbc.update(
            """
            update signal_desk_premarket_direction_forecast
            set previous_close = ?, actual_open = ?, actual_gap_rate = ?, actual_bias = ?, correct = ?, evaluated_at = now()
            where prediction_date = ?
            """.trimIndent(),
            previous, today.open, gapRate, actualBias.name, correct, java.sql.Date.valueOf(date),
        )
        if (updated == 0) return EvaluationResult(false, gapRate, null)
        log.info("premarket forecast evaluated — date={} gap={} actual={} correct={}", date, gapRate, actualBias, correct)
        return EvaluationResult(true, gapRate, correct)
    }

    companion object {
        private const val DEFAULT_STATS_WINDOW = 20
        /** ±0.05% 이내 시초 갭은 체결 오차 수준으로 보고 보합으로 분류한다. */
        private const val GAP_THRESHOLD = 0.05

        internal fun biasOf(gapRate: Double): PreMarketDirectionService.Bias = when {
            gapRate >= GAP_THRESHOLD -> PreMarketDirectionService.Bias.RISING
            gapRate <= -GAP_THRESHOLD -> PreMarketDirectionService.Bias.FALLING
            else -> PreMarketDirectionService.Bias.NEUTRAL
        }
    }

    private data class ForecastRow(
        val predictionDate: LocalDate,
        val correct: Boolean,
        val actualGapRate: Double?,
    )
}
