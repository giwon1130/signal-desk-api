package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.common.KST
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Shared validated evidence, fixed bucket weights, explicit abstention. Not a calibrated forecast. */
@Service
class PreMarketDirectionService(
    private val evidence: MarketEvidenceService,
    private val marketSessionService: MarketSessionService,
) {
    val rulesVersion = "premarket-evidence-v3"
    fun current(): PreMarketDirection = if (isPredictionWindow()) fromReport(evidence.current()) else PreMarketDirection.EMPTY

    fun fromReport(report: MarketEvidenceReport, now: ZonedDateTime = ZonedDateTime.now(KST)): PreMarketDirection {
        if (!isPredictionWindow(now)) return PreMarketDirection.EMPTY
        val collected = runCatching { Instant.parse(report.asOf) }.getOrNull()
        if (collected == null || collected > now.toInstant() || Duration.between(collected, now).seconds > 120 ||
            !isPredictionWindow(collected.atZone(KST))) return PreMarketDirection.EMPTY.copy(
                status = "INSUFFICIENT", rulesVersion = rulesVersion, biasLabel = "최신 자료 확인 중", summary = "최신 장전 자료가 없어 방향 판단을 보류합니다.")
        val signal = computeSignal(report)
        val usable = report.evidence.filter { it.status == "OBSERVED" && it.change?.isFinite() == true && it.value?.isFinite() == true }
        fun quote(e: MetricEvidence) = DirectionQuote(e.label, e.change!!, e.value!!, e.observedAt, e.source, e.id != "KR_NIGHT")
        val night = usable.firstOrNull { it.id == "KR_NIGHT" }?.let(::quote)
        val overseas = usable.filter { it.id in setOf("^GSPC", "^IXIC", "EWY", "SOXX", "MU", "SKHY", "SMSN.IL", "ES=F", "KRW=X") }.map(::quote)
        val status = when {
            signal.coverage < MIN_COVERAGE -> "INSUFFICIENT"
            report.riskLevel != "NORMAL" -> "RISK_CAUTION"
            signal.bias == null -> "CONFLICTING"
            else -> "REFERENCE"
        }
        val bias = signal.bias.takeIf { status == "REFERENCE" }
        return PreMarketDirection(
            locked = false, kospiFutures = null, overseas = overseas, nightFutures = night,
            bias = bias?.name, biasLabel = when {
                status == "INSUFFICIENT" -> "방향을 판단할 자료가 부족합니다"
                status == "RISK_CAUTION" -> "위험 요인 확인이 먼저입니다"
                bias == Bias.RISING -> "해외 참고지표는 우호적입니다"
                bias == Bias.FALLING -> "해외 참고지표는 부담을 나타냅니다"
                else -> "해외 참고지표가 엇갈리고 있습니다"
            },
            summary = "밤사이 확인된 시장 여건입니다. 오늘 시초가나 장중 상승·하락을 확정하는 전망이 아닙니다.",
            sessionActive = false, asOf = report.asOf,
            score = signal.score.takeIf { bias != null }, confidence = if (bias == null) "INSUFFICIENT" else "LOW",
            coverage = (signal.coverage * 100).roundToInt(), inputCount = overseas.size + if (night != null) 1 else 0,
            status = status, rulesVersion = rulesVersion,
            warnings = listOf(
                if (night == null) "한국 야간선물 실측은 확인되지 않아 제외했습니다. 해외 ETF와 미국 선물은 한국 야간선물이 아닙니다."
                else "한국 야간선물은 제공사 기준 전일 대비 값입니다. 계약 만기와 관측 시각을 확인한 자료만 반영했습니다.",
                "각 해외시장 전일 대비 변화입니다. 한국장 마감 이후 수익률이나 원화 환산 수익률과는 다릅니다.",
                "자료가 빠져도 다른 지표의 비중을 늘리지 않습니다. 개장 전망의 예측 성능은 아직 검증되지 않았습니다.",
            ),
        )
    }

    internal fun computeSignal(report: MarketEvidenceReport): DirectionSignal {
        // Local cash is yesterday's KR session, daily published rates are background, not overnight returns.
        val factors = report.factors.filter { it.id in OVERNIGHT_BUCKETS }
        val available = factors.filter { it.score != null && it.score.isFinite() && it.coverage >= .5 }
        val coverage = available.sumOf { it.weight * it.coverage } / TOTAL_WEIGHT
        val score = available.sumOf { it.weight * it.score!! } / TOTAL_WEIGHT * 100
        val positive = available.filter { it.score!! >= .2 }.sumOf { it.weight }
        val negative = available.filter { it.score!! <= -.2 }.sumOf { it.weight }
        val bias = when {
            coverage < MIN_COVERAGE || available.size < 3 -> null
            positive >= .2 && negative >= .2 -> null
            score >= 20 -> Bias.RISING
            score <= -20 -> Bias.FALLING
            else -> null
        }
        return DirectionSignal(bias, score, coverage.coerceIn(0.0, 1.0))
    }

    internal fun isPredictionWindow(now: ZonedDateTime = ZonedDateTime.now(KST)): Boolean {
        val local = now.withZoneSameInstant(KST)
        return marketSessionService.isKrTradingDay(local.toLocalDate()) &&
            local.toLocalTime() >= LocalTime.of(6, 30) && local.toLocalTime() < LocalTime.of(9, 0)
    }

    enum class Bias { RISING, NEUTRAL, FALLING }
    internal data class DirectionSignal(val bias: Bias?, val score: Double, val coverage: Double)
    companion object {
        private val OVERNIGHT_BUCKETS = setOf("us_equity", "us_futures", "kr_proxy", "semiconductors", "fx", "kr_night")
        private const val TOTAL_WEIGHT = .75 // Fixed full model weight, NEVER divide by available weight.
        private const val MIN_COVERAGE = .65
    }
}
