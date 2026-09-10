package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.common.KST
import com.giwon.signaldesk.features.market.application.*
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

data class EvidenceQualitySummary(val metricId: String, val statusCounts: Map<String, Int>)
data class EvidenceReplaySummary(
    val evaluatedRulesVersion: String, val sourceRulesVersions: List<String>,
    val snapshotCount: Int, val rejectedCount: Int, val truncated: Boolean,
    val sameVersionCount: Int, val sameVersionDriftCount: Int, val changedDecisionCount: Int,
    val averageCoveragePercent: Double?, val insufficientCount: Int,
    val quality: List<EvidenceQualitySummary>,
)
data class ExploratoryOpenGapSummary(
    val status: String, val preopenDays: Int, val directionalDays: Int,
    val abstainedDays: Int, val unavailableOutcomeDays: Int,
    val matchedDays: Int, val matchPercent: Double?,
    val interval95LowPercent: Double?, val interval95HighPercent: Double?,
    val alwaysUpMatchPercent: Double?,
    val outcome: String = "KOSPI_OPEN_VS_PREVIOUS_TRADING_CLOSE",
    val minimumDisplaySamples: Int = 30,
    val expectedTradingDays: Int = 0,
    val missingPreopenDays: Int = 0,
)
data class MarketEvidenceEvaluation(
    val asOf: String, val from: String,
    val replay: EvidenceReplaySummary, val exploratory: ExploratoryOpenGapSummary,
    val calibrationStatus: String = "NOT_CALIBRATED",
    val parametersChanged: Boolean = false,
    val warnings: List<String> = listOf(
        "현재 조건 설명 규칙을 과거 입력에 재적용한 탐색적 검증이야. 실전 예측 성능·수익률을 보장하지 않아.",
        "한국 거래일 06:30~09:00 사이 마지막 입력 한 건만 사용해. 장 시작 후 입력은 제외하고, 자료 부족·중립·위험 경보는 판단 보류로 집계해.",
        "시초 갭은 ±0.05%를 기준으로 분류해. 방향 표본 30일 미만이면 일치율을 표시하지 않아. 30일 이상이어도 검증 완료를 뜻하지 않아.",
        "구간 추정은 독립 표본을 가정한 Wilson 95% 구간이야. 시장의 시계열 의존성을 반영하지 않아 불확실성이 더 클 수 있어.",
        "결과 라벨은 Naver의 실제 일봉만 사용해. 합성 차트는 사용하지 않으며, 과거 일봉 정정 가능성이 있어.",
        "가중치는 자동 조정하지 않아. 별도 학습 구간·미사용 검증 구간·시장 국면별 비교를 거친 뒤 변경해야 해.",
    ),
)

/** Pure replay/evaluation: never fetches current quotes or calls Gemini. */
class MarketEvidenceEvaluator(private val analyzer: MarketEvidenceAnalyzer, private val sessions: MarketSessionService) {
    fun evaluate(rows: List<ArchivedMarketEvidence>, candles: List<IndexCandle>, from: Instant, now: Instant): MarketEvidenceEvaluation {
        require(from < now)
        val bounded = rows.take(2160)
        val valid = bounded.filter { row ->
            val input = row.input
            input != null && row.report != null && input.collectedAt == row.observedAt &&
                input.collectedAt >= from && input.collectedAt < now &&
                row.bucketAt == input.collectedAt.truncatedTo(ChronoUnit.HOURS) &&
                row.report.asOf == input.collectedAt.toString() && row.report.rulesVersion == row.rulesVersion
        }.distinctBy { it.bucketAt to it.rulesVersion }
        val replayed = valid.map { it to analyzer.analyze(it.input!!) }
        val currentVersion = replayed.filter { it.first.rulesVersion == MarketEvidenceAnalyzer.RULES_VERSION }
        val replay = EvidenceReplaySummary(
            MarketEvidenceAnalyzer.RULES_VERSION, valid.map { it.rulesVersion }.distinct().sorted(),
            valid.size, bounded.size - valid.size, rows.size > 2160,
            currentVersion.size, currentVersion.count { it.first.report != it.second },
            replayed.count { (old, fresh) -> old.report!!.let {
                it.regime != fresh.regime || it.riskLevel != fresh.riskLevel || it.balanceScore != fresh.balanceScore
            } },
            replayed.map { it.second.coveragePercent }.takeIf { it.isNotEmpty() }?.average(),
            replayed.count { it.second.regime == "INSUFFICIENT_DATA" },
            replayed.flatMap { it.second.evidence }.groupBy { it.id }.toSortedMap().map { (id, metrics) ->
                EvidenceQualitySummary(id, metrics.groupingBy { it.status }.eachCount().toSortedMap())
            },
        )
        // Choose once BEFORE looking at coverage, regime or the outcome: no cherry-picking a better earlier snapshot.
        val preopen = replayed.filter { (row, _) ->
            val local = row.observedAt.atZone(KST)
            sessions.isKrTradingDay(local.toLocalDate()) && local.toLocalTime() >= LocalTime.of(6, 30) &&
                local.toLocalTime() < LocalTime.of(9, 0)
        }.groupBy { it.first.observedAt.atZone(KST).toLocalDate() }
            .mapValues { (_, xs) -> xs.maxWith(compareBy({ it.first.observedAt }, { it.first.rulesVersion })) }
        val datedCandles = candles.groupBy { runCatching { LocalDate.parse(it.date, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
        var abstained = 0
        var unavailable = 0
        var matched = 0
        var alwaysUp = 0
        var directional = 0
        for ((date, entry) in preopen.toSortedMap()) {
            val report = entry.second
            val direction = when {
                report.coveragePercent < 55 || report.regime in setOf("INSUFFICIENT_DATA", "RISK_CAUTION") ||
                    report.riskLevel in setOf("HIGH", "ELEVATED", "UNKNOWN") -> null
                (report.balanceScore ?: 0.0) >= 20 -> 1
                (report.balanceScore ?: 0.0) <= -20 -> -1
                else -> null
            }
            if (direction == null) { abstained++; continue }
            // Only use completed daily candles (16:00 KST safety buffer), not a live/partial day.
            var previousDate = date.minusDays(1)
            while (!sessions.isKrTradingDay(previousDate)) previousDate = previousDate.minusDays(1)
            val today = datedCandles[date]?.singleOrNull()
            val previous = datedCandles[previousDate]?.singleOrNull()
            if (now < date.atTime(16, 0).atZone(KST).toInstant() || !validCandle(today) || !validCandle(previous)) {
                unavailable++; continue
            }
            val gap = (today!!.open / previous!!.close - 1) * 100
            if (!gap.isFinite()) { unavailable++; continue }
            val actual = when { gap >= .05 -> 1; gap <= -.05 -> -1; else -> 0 }
            directional++
            if (direction == actual) matched++
            if (actual == 1) alwaysUp++
        }
        val expectedDays = generateSequence(from.atZone(KST).toLocalDate()) { it.plusDays(1) }
            .takeWhile { it <= now.atZone(KST).toLocalDate() }
            .filter { sessions.isKrTradingDay(it) && it.atTime(9, 0).atZone(KST).toInstant() <= now &&
                it.atTime(6, 30).atZone(KST).toInstant() >= from }.toSet()
        val display = directional >= 30 && !replay.truncated
        val interval = if (display) wilson(matched, directional) else null
        return MarketEvidenceEvaluation(now.toString(), from.toString(), replay,
            ExploratoryOpenGapSummary(when { replay.truncated -> "TRUNCATED_HISTORY"; display -> "EXPLORATORY_ONLY"; else -> "INSUFFICIENT_HISTORY" }, preopen.size,
                directional, abstained, unavailable, matched,
                if (display) 100.0 * matched / directional else null,
                interval?.first?.times(100), interval?.second?.times(100),
                if (display) 100.0 * alwaysUp / directional else null,
                expectedTradingDays = expectedDays.size, missingPreopenDays = (expectedDays - preopen.keys).size))
    }

    private fun validCandle(c: IndexCandle?): Boolean = c != null &&
        listOf(c.open, c.high, c.low, c.close).all { it.isFinite() && it > 0 } &&
        c.low <= minOf(c.open, c.close) && c.high >= maxOf(c.open, c.close) && c.high >= c.low

    private fun wilson(successes: Int, n: Int): Pair<Double, Double> {
        val z = 1.959963984540054
        val p = successes.toDouble() / n
        val denominator = 1 + z * z / n
        val center = (p + z * z / (2 * n)) / denominator
        val half = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denominator
        return (center - half).coerceAtLeast(0.0) to (center + half).coerceAtMost(1.0)
    }
}

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MarketEvidenceEvaluationService(
    private val archive: MarketEvidenceArchive, private val analyzer: MarketEvidenceAnalyzer,
    private val sessions: MarketSessionService, private val charts: NaverIndexChartClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable(cacheNames = ["market-insight"], key = "'evidence-evaluation-v2:' + #days", sync = true)
    fun evaluate(days: Int): MarketEvidenceEvaluation {
        require(days in 1..90) { "days must be between 1 and 90" }
        val now = clock.instant()
        val from = now.minus(Duration.ofDays(days.toLong()))
        val rows = archive.read(from, now)
        val candles = if (rows.isEmpty()) emptyList() else runCatching {
            charts.fetchOhlc("KOSPI", NaverIndexChartClient.PeriodType.DAILY, 100)
        }.onFailure { log.warn("Evidence evaluation outcome feed unavailable ({})", it.javaClass.simpleName) }.getOrDefault(emptyList())
        return MarketEvidenceEvaluator(analyzer, sessions).evaluate(rows, candles, from, now)
    }
}
