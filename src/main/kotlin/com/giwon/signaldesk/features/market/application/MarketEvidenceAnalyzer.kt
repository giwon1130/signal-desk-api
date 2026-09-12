package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Component
import java.time.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Replayable inputs. collectedAt is NOT the observation time of any quote. */
data class MarketEvidenceInput(
    val collectedAt: Instant,
    val quotes: List<GlobalIndex>,
    val macro: MacroSnapshot?,
    val headlines: List<MarketNews>?,
    val nightFutures: KrxNightFuturesObservation? = null,
)

data class MetricEvidence(
    val id: String, val label: String, val source: String?, val sourceUrl: String?,
    val observedAt: String?, val observationDate: String?, val status: String,
    val value: Double?, val change: Double?, val unit: String, val detail: String,
)

data class EvidenceFactor(
    val id: String, val label: String, val weight: Double, val score: Double?,
    val coverage: Double, val evidenceIds: List<String>, val interpretation: String,
)

data class MarketEvidenceReport(
    val rulesVersion: String = MarketEvidenceAnalyzer.RULES_VERSION,
    val asOf: String, val horizon: String, val regime: String, val riskLevel: String,
    val coveragePercent: Int, val balanceScore: Double?,
    val headline: String, val conclusion: String,
    val factors: List<EvidenceFactor>, val evidence: List<MetricEvidence>,
    val warnings: List<String>, val newsEvidence: List<MarketNews>,
)

/**
 * Conservative, uncalibrated explanatory rules — not a return forecast or trade signal.
 * Missing weights are NOT redistributed. Correlated semiconductor proxies share one capped bucket.
 * Monthly macro, undated ranks, synthetic charts and experimental footfall signals never vote.
 */
@Component
class MarketEvidenceAnalyzer(private val sessions: MarketSessionService) {
    fun analyze(input: MarketEvidenceInput): MarketEvidenceReport {
        val now = input.collectedAt
        val evidence = linkedMapOf<String, MetricEvidence>()
        val quotes = input.quotes.groupBy { it.symbol }.mapValues { (_, xs) ->
            xs.maxByOrNull { parseInstant(it.observedAt) ?: Instant.MIN }!!
        }
        fun quote(id: String, label: String, market: String, maxAgeHours: Long = 96): MetricEvidence {
            val q = quotes[id]
            val time = parseInstant(q?.observedAt)
            val date = parseDate(q?.observationDate)
            val prevDate = parseDate(q?.previousObservationDate)
            val expected = expectedSession(now, market)
            val valid = q != null && q.value.isFinite() && q.value > 0 && q.changeRate.isFinite() &&
                q.previousValue?.let { it.isFinite() && it > 0 } == true &&
                abs(q.changeRate - (q.value / q.previousValue - 1) * 100) < 0.02
            val status = when {
                q == null -> "MISSING"
                !valid -> "INVALID"
                time == null || date == null || prevDate == null || q.source.isNullOrBlank() -> "UNDATED"
                time > now.plusSeconds(300) || date > now.atZone(zone(market)).toLocalDate() || prevDate >= date -> "INVALID"
                Duration.between(time, now).toHours() > maxAgeHours || date < expected || prevDate < date.minusDays(7) -> "STALE"
                isRegular(now, market) && Duration.between(time, now).toMinutes() > 90 -> "STALE"
                else -> "OBSERVED"
            }
            val detail = if (status == "OBSERVED") {
                "$label ${fmt(q!!.value)}${if (id == "KRW=X") "원" else ""}, 이전 거래일 대비 ${signed(q.changeRate)}% · 관측 ${q.observedAt}"
            } else "$label: ${qualityLabel(status)} — 방향 판단에서 제외했습니다. 관측 ${q?.observedAt ?: "시각 미확인"}"
            return MetricEvidence(id, label, q?.source, "https://finance.yahoo.com/quote/${java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8)}/",
                q?.observedAt, q?.observationDate, status, q?.value?.takeIf { it.isFinite() },
                q?.changeRate?.takeIf { it.isFinite() }, "PERCENT_CHANGE", detail).also { evidence[id] = it }
        }
        quote("^GSPC", "S&P500", "US")
        quote("^IXIC", "나스닥", "US")
        quote("^KS11", "코스피", "KR")
        quote("^KQ11", "코스닥", "KR")
        quote("ES=F", "S&P500 선물", "FUTURES", 30)
        quote("EWY", "한국 주식 ETF(EWY·달러 표시)", "US")
        quote("SOXX", "미국 반도체 ETF(SOXX)", "US")
        quote("MU", "마이크론", "US")
        quote("SKHY", "SK하이닉스 ADR", "US")
        quote("SMSN.IL", "삼성전자 런던 GDR", "UK")
        quote("KRW=X", "원/달러", "FX", 30)
        quote("CL=F", "WTI 선물", "FUTURES", 30)
        quote("^VIX", "VIX", "US")

        fun rate(id: String, label: String, s: FredSeriesSnapshot?): MetricEvidence {
            val date = parseDate(s?.observationDate)
            val previousDate = parseDate(s?.previousObservationDate)
            val expected = expectedSession(now, "US")
            // Official daily releases can lag one US business day. Always label them delayed, never live.
            var oldest = expected.minusDays(1)
            while (!sessions.isUsTradingDay(oldest)) oldest = oldest.minusDays(1)
            val delta = if (s?.previousValue != null) (s.currentValue - s.previousValue) * 100 else null
            val status = when {
                s == null -> "MISSING"
                s.frequency != "DAILY" || s.source != "FRED:$id" -> "INVALID"
                date == null || previousDate == null -> "UNDATED"
                !s.currentValue.isFinite() || s.currentValue !in 0.0..25.0 || delta == null || !delta.isFinite() ||
                    previousDate >= date || date > now.atZone(zone("US")).toLocalDate() -> "INVALID"
                date < oldest || previousDate < date.minusDays(7) -> "STALE"
                else -> "DELAYED"
            }
            return MetricEvidence(id, label, s?.source, "https://fred.stlouisfed.org/series/$id", null,
                s?.observationDate, status, s?.currentValue?.takeIf { it.isFinite() }, delta?.takeIf { it.isFinite() }, "BASIS_POINTS",
                if (status == "DELAYED") "$label ${fmt(s!!.currentValue)}%, 이전 관측 대비 ${signed(delta!!)}bp · ${s.observationDate} 일간 공표치(실시간 아님)"
                else "$label: ${qualityLabel(status)} — 방향 판단에서 제외했습니다. 관측 ${s?.observationDate ?: "날짜 미확인"}").also { evidence[id] = it }
        }
        rate("DGS2", "미 국채 2년물", input.macro?.treasury2y)
        rate("DGS10", "미 국채 10년물", input.macro?.treasury10y)
        evidence["KR_NIGHT"] = KrxNightFuturesEvidence(sessions).assess(input.nightFutures, now)

        fun usable(id: String) = evidence[id]?.takeIf { it.status in setOf("OBSERVED", "DELAYED") }
        val stockWeak = listOf("^GSPC", "^IXIC").mapNotNull { usable(it)?.change }.let { it.size == 2 && it.average() <= -0.5 }
        fun group(id: String, label: String, weight: Double, members: Map<String, Double>, scale: Double,
                  transform: (Double) -> Double = { it }): EvidenceFactor {
            val available = members.filterKeys { usable(it) != null }
            val coverage = available.values.sum()
            // Fixed member weights; a lone ADR cannot take over the whole semiconductor bucket.
            val score = available.entries.sumOf { (key, w) -> transform(usable(key)!!.change!! / scale).coerceIn(-1.0, 1.0) * w }
            val interpretation = when {
                coverage < 0.5 -> "자료가 부족해 이 영역의 방향을 판단하지 않았습니다."
                score >= 0.2 -> "주식시장에 우호적인 흐름을 보이고 있습니다."
                score <= -0.2 -> "주식시장에 부담을 주는 흐름을 보이고 있습니다."
                else -> "방향이 뚜렷하지 않거나 서로 엇갈리고 있습니다."
            }
            return EvidenceFactor(id, label, weight, score.takeIf { coverage >= 0.5 }, coverage,
                members.keys.toList(), interpretation)
        }
        val factors = listOf(
            group("kr_cash", "한국 현물", .10, mapOf("^KS11" to .6, "^KQ11" to .4), 1.5),
            group("us_equity", "미국 주식", .15, mapOf("^GSPC" to .6, "^IXIC" to .4), 1.5),
            group("us_futures", "미국 선물", .10, mapOf("ES=F" to 1.0), 1.0),
            group("kr_proxy", "한국 해외 ETF", .10, mapOf("EWY" to 1.0), 2.0),
            group("semiconductors", "반도체 동행 지표", .20,
                mapOf("SOXX" to .5, "MU" to .2, "SKHY" to .2, "SMSN.IL" to .1), 2.0),
            group("fx", "환율 부담", .10, mapOf("KRW=X" to 1.0), .7) { -it },
            group("rates", "미국 금리 부담", .15, mapOf("DGS2" to .4, "DGS10" to .6), 10.0) {
                if (it < 0 && stockWeak) 0.0 else (-it).coerceAtMost(.5)
            },
            group("kr_night", "한국 야간선물", .10, mapOf("KR_NIGHT" to 1.0), 1.0),
        )
        val coverage = factors.filter { it.score != null }.sumOf { it.weight * it.coverage }
        val balance = factors.sumOf { it.weight * (it.score ?: 0.0) }
        val positive = factors.filter { (it.score ?: 0.0) >= .2 }.sumOf { it.weight }
        val negative = factors.filter { (it.score ?: 0.0) <= -.2 }.sumOf { it.weight }
        val usableGroups = factors.count { it.score != null }
        val news = recentNews(input.headlines.orEmpty(), now)
        val escalation = news.filter { isEscalation(it.title) }
        val sources = escalation.map { it.source.trim().lowercase(Locale.ROOT) }.distinct().size
        val vix = usable("^VIX")?.value
        val oil = usable("CL=F")?.change
        val highRisk = (vix != null && vix >= 30) || (sources >= 3 && ((oil ?: 0.0) >= 2 || (vix ?: 0.0) >= 25))
        val yield2 = usable("DGS2")
        val yield10 = usable("DGS10")
        val risk = when {
            highRisk -> "HIGH"
            (vix != null && vix >= 25) || (oil != null && oil >= 3) || sources >= 2 -> "ELEVATED"
            vix == null || oil == null || news.isEmpty() -> "UNKNOWN"
            else -> "NORMAL"
        }
        val regime = when {
            coverage < .55 || usableGroups < 4 -> "INSUFFICIENT_DATA"
            highRisk -> "RISK_CAUTION"
            positive >= .2 && negative >= .2 -> "MIXED"
            balance >= .2 && risk !in setOf("ELEVATED", "UNKNOWN") -> "SUPPORTIVE"
            balance <= -.2 -> "PRESSURED"
            else -> "MIXED"
        }
        val headline = when (regime) {
            "INSUFFICIENT_DATA" -> "시황 판단에 필요한 최신 자료가 부족합니다"
            "RISK_CAUTION" -> "시장 방향보다 위험 요인을 먼저 확인할 때입니다"
            "SUPPORTIVE" -> "시장에 우호적인 지표가 우세합니다"
            "PRESSURED" -> "주식시장에 부담을 주는 지표가 우세합니다"
            else -> "긍정 요인과 부담 요인이 엇갈리고 있습니다"
        }
        val warnings = buildList {
            add("이 분석은 현재 시장 조건을 설명하며 상승 확률, 예상 수익률 또는 매수·매도 지시를 제공하지 않습니다.")
            add("관측 시각이 다른 시장의 자료를 함께 비교했습니다. Yahoo 시세는 비공식 지연 시세일 수 있으며 휴장 시에는 최근 거래일 값이 사용됩니다.")
            if (usable("KR_NIGHT") == null) add("야간선물 실측이 연결되지 않았거나 유효한 관측값이 부족해 이번 분석에서 제외했습니다.")
            add("해외 ETF와 ADR에는 환율, 거래시간 및 괴리율의 영향이 있습니다.")
            add("수급 순위만으로 시장 전체 순매수 규모를 추정하지 않았습니다. 관측 시각이 없는 수급과 월간 지표는 단기 판단에서 제외했습니다.")
            if (yield2 != null && yield10 != null && yield2.observationDate == yield10.observationDate) {
                val spread = (yield10.value!! - yield2.value!!) * 100
                add("미 국채 장단기 금리차(10년−2년)는 ${signed(spread)}bp입니다(${yield10.observationDate}). 경기와 정책의 배경 지표로만 사용하며 당일 주가 방향으로 단정하지 않았습니다.")
            }
            if (stockWeak && listOf("DGS2", "DGS10").any { (usable(it)?.change ?: 0.0) < 0 })
                add("주가와 금리가 함께 내려 금리 하락을 호재로 가산하지 않았습니다. 경기 우려 가능성도 함께 확인해야 합니다.")
            if (input.headlines == null) add("뉴스를 수집하지 못했으며, 이것이 지정학 위험이 낮다는 의미는 아닙니다.")
            else if (news.isEmpty()) add("최근 24시간의 유효 뉴스가 없어 지정학 위험을 확정하지 않았습니다.")
            if (escalation.isNotEmpty()) add("최근 24시간 지정학 긴장 관련 교차 보도는 ${escalation.size}건, ${sources}개 출처입니다. 제목 기반 경보이며 사건의 사실 확인이나 전쟁 확률을 의미하지 않습니다.")
            if (news.any { isDeescalation(it.title) } && escalation.isNotEmpty()) add("긴장 완화와 휴전 보도도 함께 있어 전쟁 악화로 단정하지 않았습니다.")
            if (risk == "UNKNOWN") add("일부 위험 자료가 부족해 위험 수준을 확정하지 않았습니다.")
        }
        return MarketEvidenceReport(asOf = now.toString(), horizon = "CURRENT_CONDITIONS_KR_WITH_GLOBAL_CONTEXT",
            regime = regime, riskLevel = risk, coveragePercent = (coverage * 100).roundToInt(),
            balanceScore = (balance * 100).takeIf { regime != "INSUFFICIENT_DATA" }, headline = headline,
            conclusion = "$headline. 수집 대상 중 ${(coverage * 100).roundToInt()}%를 분석에 반영했으며, 누락된 지표의 비중은 다른 지표에 더하지 않았습니다.",
            factors = factors, evidence = evidence.values.toList(), warnings = warnings, newsEvidence = escalation.take(8))
    }

    internal fun expectedSession(now: Instant, market: String): LocalDate {
        val local = now.atZone(zone(market))
        val open = when (market) { "KR" -> LocalTime.of(9, 0); "UK" -> LocalTime.of(8, 0); else -> LocalTime.of(9, 30) }
        var date = if (local.toLocalTime() < open) local.toLocalDate().minusDays(1) else local.toLocalDate()
        fun trading(d: LocalDate) = when (market) {
            "KR" -> sessions.isKrTradingDay(d)
            "US" -> sessions.isUsTradingDay(d)
            else -> d.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
        while (!trading(date)) date = date.minusDays(1)
        return date
    }

    private fun isRegular(now: Instant, market: String): Boolean =
        market in setOf("KR", "US") && sessions.buildMarketSessions(now.atZone(ZoneOffset.UTC))
            .any { it.market == market && it.phase == "REGULAR" }

    private fun zone(market: String): ZoneId = ZoneId.of(when (market) {
        "KR" -> "Asia/Seoul"; "UK" -> "Europe/London"; else -> "America/New_York"
    })

    internal fun recentNews(news: List<MarketNews>, now: Instant): List<MarketNews> = news
        .filter { n -> parseInstant(n.publishedAt)?.let { it <= now && it >= now.minus(Duration.ofHours(24)) } == true }
        .filter { it.source.isNotBlank() && it.source != "Google News" && it.url.startsWith("https://") }
        .distinctBy { it.url.substringBefore('?') }
        .distinctBy { it.title.substringBeforeLast(" - ").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "") }
        .sortedByDescending { it.publishedAt }.take(80)

    internal fun isDeescalation(title: String): Boolean = Regex("휴전|종전|평화|긴장 완화|ceasefire|peace talks|de.escalat", RegexOption.IGNORE_CASE).containsMatchIn(title)
    internal fun isEscalation(title: String): Boolean {
        if (Regex("무역.?전쟁|가격.?전쟁|관세.?전쟁|전쟁.?없|공격.?부인|확전.?없|no war|denies attack|trade war", RegexOption.IGNORE_CASE).containsMatchIn(title)) return false
        if (isDeescalation(title) && !Regex("휴전.*(붕괴|위반|결렬)|ceasefire.*(collapse|violat)", RegexOption.IGNORE_CASE).containsMatchIn(title)) return false
        return Regex("전쟁|공습|미사일|군사.?공격|침공|해협.?봉쇄|확전|\\bwar\\b|airstrike|missile|invasion|military attack|blockade", RegexOption.IGNORE_CASE).containsMatchIn(title)
    }

    private fun qualityLabel(status: String) = when (status) {
        "MISSING" -> "자료 미확보"; "STALE" -> "오래된 관측값"; "UNDATED" -> "관측 시각/출처 미확인"; else -> "값/단위 검증 실패"
    }
    private fun parseInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private fun parseDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
    private fun signed(value: Double) = String.format(Locale.US, "%+.2f", value)

    companion object { const val RULES_VERSION = "market-evidence-v2" }
}
