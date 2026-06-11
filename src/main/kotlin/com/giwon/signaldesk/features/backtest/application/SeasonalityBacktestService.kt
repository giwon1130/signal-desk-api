package com.giwon.signaldesk.features.backtest.application

import com.giwon.signaldesk.features.market.application.HistoryBar
import com.giwon.signaldesk.features.market.application.YahooQuoteClient
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import kotlin.math.abs

/**
 * 시즈널리티 백테스트 — 종목 하나의 장기 일봉으로 월별/요일별 '역사적' 패턴을 정직한 통계로 산출.
 *
 * 설계 원칙(과최적화·도박조장 방지):
 *  1) 평균이 아니라 일관성 — 월별 승률(N년 중 양봉 비율) + 중앙값으로 "한두 해가 끌어올린" 경우 가려냄.
 *  2) 거래비용 반영 — 작은 엣지는 수수료가 먹으니 비용후 수익률을 항상 같이 노출.
 *  3) 전부 노출 — 12개월 전부를 등급(STRONG/WEAK/NOISE)과 함께. 'best만 줍기' 금지.
 *
 * 데이터: US=야후 ticker, KR=야후 .KS/.KQ. AI 미사용(결정론적 통계). 캐시 24h.
 */
@Service
class SeasonalityBacktestService(
    private val yahooQuoteClient: YahooQuoteClient,
) {
    // cost 가 키에 빠지면 다른 costPct 요청에 첫 캐시가 그대로 반환된다. sync=true 로 동시 미스 single-flight.
    @Cacheable(cacheNames = ["seasonality"], key = "#market + ':' + #ticker + ':' + #years + ':' + #costPct", unless = "#result == null", sync = true)
    fun report(market: String, ticker: String, name: String, years: Int, costPct: Double): SeasonalityReport? {
        val span = years.coerceIn(3, 20)
        val bars = loadBars(market, ticker, "${span}y")
        return computeReport(bars, market, ticker, name, costPct.coerceIn(0.0, 2.0))
    }

    /** 순수 계산부 — 네트워크 없이 합성 bars 로 검증 가능. */
    fun computeReport(bars: List<HistoryBar>, market: String, ticker: String, name: String, cost: Double): SeasonalityReport? {
        val sorted = bars.sortedBy { it.date }
        if (sorted.size < MIN_BARS) return null

        val monthly = monthlyStats(sorted, cost)
        val weekday = weekdayStats(sorted)
        val weekend = weekendTrade(sorted, cost)
        val historyYears = round2((sorted.last().date.toEpochDay() - sorted.first().date.toEpochDay()) / 365.25)

        return SeasonalityReport(
            market = market.uppercase(),
            ticker = ticker,
            name = name.ifBlank { ticker },
            history = HistoryMeta(
                years = historyYears, from = sorted.first().date.toString(), to = sorted.last().date.toString(),
                bars = sorted.size, source = "Yahoo (배당·분할 조정)",
            ),
            costAssumptionPct = cost,
            monthly = monthly,
            weekday = weekday,
            weekendTrade = weekend,
            highlights = highlights(monthly),
            caveats = buildCaveats(historyYears, monthly),
        )
    }

    private fun loadBars(market: String, ticker: String, range: String): List<HistoryBar> = when (market.uppercase()) {
        "US" -> yahooQuoteClient.fetchDailyHistory(ticker, range)
        // KR: 코스피(.KS) 우선. 야후는 코스닥도 .KS 로 받아주지만, .KS 가 부실(빈값/짧음)하면
        // 코스닥(.KQ)도 받아 더 긴 쪽을 쓴다(상장 종목별 커버리지 차이 방어).
        "KR" -> {
            val ks = yahooQuoteClient.fetchDailyHistory("$ticker.KS", range)
            if (ks.size >= MIN_BARS) ks
            else yahooQuoteClient.fetchDailyHistory("$ticker.KQ", range).let { kq -> if (kq.size > ks.size) kq else ks }
        }
        else -> emptyList()
    }

    // ─── 가설 빌더: 커스텀 윈도우(매년 진입일~청산일 보유) 백테스트 ───────────
    fun customWindow(
        market: String, ticker: String, name: String,
        entryMonth: Int, entryDay: Int, exitMonth: Int, exitDay: Int, cost: Double,
    ): CustomBacktestResult? {
        if (entryMonth !in 1..12 || exitMonth !in 1..12 || entryDay !in 1..31 || exitDay !in 1..31) return null
        val bars = loadBars(market, ticker, "15y").sortedBy { it.date }
        if (bars.size < MIN_BARS) return null
        // 청산이 진입보다 달력상 앞이면 '이듬해' 청산(예: 12/20 → 1/31).
        val crossYear = exitMonth < entryMonth || (exitMonth == entryMonth && exitDay <= entryDay)
        val perYear = ArrayList<YearReturn>()
        for (y in bars.map { it.date.year }.toSortedSet()) {
            val entryT = safeDate(y, entryMonth, entryDay) ?: continue
            val exitT = safeDate(if (crossYear) y + 1 else y, exitMonth, exitDay) ?: continue
            // 목표일 당일~10일 내 첫 거래일(주말·휴장 보정)
            val entryBar = bars.firstOrNull { !it.date.isBefore(entryT) && it.date.isBefore(entryT.plusDays(10)) } ?: continue
            val exitBar = bars.firstOrNull { !it.date.isBefore(exitT) && it.date.isBefore(exitT.plusDays(10)) } ?: continue
            if (exitBar.date.isAfter(entryBar.date) && entryBar.adjClose > 0) {
                perYear.add(YearReturn(y, round2((exitBar.adjClose / entryBar.adjClose - 1) * 100)))
            }
        }
        if (perYear.size < 3) return null
        val rets = perYear.map { it.returnPct }
        val mean = rets.average(); val med = median(rets)
        val winRate = rets.count { it > 0 }.toDouble() / rets.size * 100
        return CustomBacktestResult(
            market = market.uppercase(), ticker = ticker, name = name.ifBlank { ticker },
            window = "${entryMonth}/${entryDay} → ${exitMonth}/${exitDay}${if (crossYear) " (이듬해)" else ""}",
            meanPct = round2(mean), medianPct = round2(med), winRatePct = round1(winRate),
            sampleYears = rets.size, worstYearPct = round2(rets.min()), bestYearPct = round2(rets.max()),
            netAfterCostPct = round2(mean - cost), tier = tierOf(winRate, mean, med, rets.size),
            costPct = cost, perYear = perYear.sortedByDescending { it.year },
        )
    }

    private fun safeDate(y: Int, m: Int, d: Int): java.time.LocalDate? =
        runCatching { java.time.LocalDate.of(y, m, minOf(d, java.time.YearMonth.of(y, m).lengthOfMonth())) }.getOrNull()

    // ─── 월별 ──────────────────────────────────────────────────────────────
    private fun monthlyStats(bars: List<HistoryBar>, cost: Double): List<MonthStat> {
        // (연,월) → 그 달 마지막 거래일 종가 (bars 오름차순이라 마지막 write = 월말)
        val monthEnd = LinkedHashMap<Pair<Int, Int>, Double>()
        for (b in bars) monthEnd[b.date.year to b.date.monthValue] = b.adjClose
        val keys = monthEnd.keys.toList()

        val byMonth = HashMap<Int, MutableList<Double>>()
        // 마지막 (연,월)은 진행 중일 수 있어 표본에서 제외 — 월중에 조회하면 "월초~오늘"의
        // 부분 수익률이 그 달 통계·등급을 오염시킨다. 완결이 보장되는 건 뒤에 다음 달이
        // 존재하는 구간뿐이라, 최종 월 수익률 1개를 버리는 비용으로 정확성을 지킨다.
        for (i in 1 until keys.size - 1) {
            val prev = monthEnd[keys[i - 1]]!!
            val curr = monthEnd[keys[i]]!!
            if (prev <= 0.0) continue
            byMonth.getOrPut(keys[i].second) { mutableListOf() }.add((curr / prev - 1) * 100)
        }

        return (1..12).map { m ->
            val rets = byMonth[m].orEmpty()
            if (rets.isEmpty()) {
                MonthStat(m, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, "NOISE")
            } else {
                val mean = rets.average()
                val med = median(rets)
                val winRate = rets.count { it > 0 }.toDouble() / rets.size * 100
                MonthStat(
                    month = m, meanPct = round2(mean), medianPct = round2(med),
                    winRatePct = round1(winRate), sampleYears = rets.size,
                    worstYearPct = round2(rets.min()), bestYearPct = round2(rets.max()),
                    netAfterCostPct = round2(mean - cost), tier = tierOf(winRate, mean, med, rets.size),
                )
            }
        }
    }

    // ─── 요일 ──────────────────────────────────────────────────────────────
    private fun weekdayStats(bars: List<HistoryBar>): List<DayStat> {
        val byWd = HashMap<Int, MutableList<Double>>()
        for (i in 1 until bars.size) {
            val p = bars[i - 1]; val c = bars[i]
            if (p.adjClose <= 0.0) continue
            val wd = c.date.dayOfWeek.value // 1=Mon..7=Sun
            if (wd in 1..5) byWd.getOrPut(wd) { mutableListOf() }.add((c.adjClose / p.adjClose - 1) * 100)
        }
        val labels = mapOf(1 to "월", 2 to "화", 3 to "수", 4 to "목", 5 to "금")
        return (1..5).map { wd ->
            val rets = byWd[wd].orEmpty()
            DayStat(
                weekday = wd, label = labels[wd] ?: "$wd",
                meanPct = if (rets.isEmpty()) 0.0 else round3(rets.average()),
                winRatePct = if (rets.isEmpty()) 0.0 else round1(rets.count { it > 0 }.toDouble() / rets.size * 100),
                n = rets.size,
            )
        }
    }

    // ─── 주말효과 (금 종가 → 월 종가) ──────────────────────────────────────
    private fun weekendTrade(bars: List<HistoryBar>, cost: Double): WeekendTrade? {
        val rets = ArrayList<Double>()
        for (i in 1 until bars.size) {
            val p = bars[i - 1]; val c = bars[i]
            if (p.date.dayOfWeek.value == 5 && c.date.dayOfWeek.value == 1 && p.adjClose > 0.0) {
                rets.add((c.adjClose / p.adjClose - 1) * 100)
            }
        }
        if (rets.isEmpty()) return null
        val mean = rets.average()
        return WeekendTrade(
            meanPct = round3(mean), winRatePct = round1(rets.count { it > 0 }.toDouble() / rets.size * 100),
            n = rets.size, netAfterCostPct = round3(mean - cost),
        )
    }

    // ─── 등급 / 하이라이트 ─────────────────────────────────────────────────
    /** 일관성(승률 극단) + 평균·중앙값 동일부호 + 표본으로 등급. */
    private fun tierOf(winRate: Double, mean: Double, median: Double, n: Int): String {
        if (n < MIN_YEARS) return "NOISE"
        val sameSign = (mean >= 0) == (median >= 0)
        return when {
            (winRate >= 70 || winRate <= 30) && sameSign -> "STRONG"
            (winRate >= 60 || winRate <= 40) -> "WEAK"
            else -> "NOISE"
        }
    }

    /** STRONG 월만 추려 실행 규칙 카드로(상승=매수 후보, 하락=회피). 비용후 유효한 것만 매수 카드. */
    private fun highlights(monthly: List<MonthStat>): List<RuleCard> =
        monthly.filter { it.tier == "STRONG" }
            .sortedByDescending { abs(it.meanPct) }
            .mapNotNull { s ->
                when {
                    s.meanPct > 0 && s.netAfterCostPct > 0 -> RuleCard(
                        kind = "BUY_MONTH", month = s.month, tier = s.tier,
                        title = "${s.month}월 강세",
                        detail = "평균 ${signed(s.meanPct)}% · ${s.sampleYears}년 중 ${(s.winRatePct / 100 * s.sampleYears).toInt()}년 상승 · 비용후 ${signed(s.netAfterCostPct)}% · 최악해 ${signed(s.worstYearPct)}%",
                    )
                    s.meanPct < 0 -> RuleCard(
                        kind = "AVOID_MONTH", month = s.month, tier = s.tier,
                        title = "${s.month}월 약세 — 회피/주의",
                        detail = "평균 ${signed(s.meanPct)}% · ${s.sampleYears}년 중 ${((100 - s.winRatePct) / 100 * s.sampleYears).toInt()}년 하락 · 최악해 ${signed(s.worstYearPct)}%",
                    )
                    else -> null
                }
            }

    private fun buildCaveats(years: Double, monthly: List<MonthStat>): List<String> = buildList {
        if (years < 8) add("히스토리 ${round1(years)}년 — 표본이 적어 신뢰도가 낮습니다(8년 이상 권장).")
        if (monthly.all { it.tier == "NOISE" }) add("뚜렷한 계절성 신호가 없습니다 — 이 종목은 시즈널리티가 약합니다.")
        add("과거 통계이며 미래를 보장하지 않습니다. 알려진 패턴은 시간이 지나면 사라지기도 합니다.")
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun signed(x: Double) = if (x >= 0) "+${round2(x)}" else round2(x).toString()
    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun round2(x: Double) = Math.round(x * 100) / 100.0
    private fun round3(x: Double) = Math.round(x * 1000) / 1000.0

    companion object {
        private const val MIN_BARS = 250        // 최소 ~1년치 일봉
        private const val MIN_YEARS = 8         // 등급 STRONG/WEAK 인정 최소 표본 연수
    }
}

// ─── DTO ───────────────────────────────────────────────────────────────────
data class SeasonalityReport(
    val market: String,
    val ticker: String,
    val name: String,
    val history: HistoryMeta,
    val costAssumptionPct: Double,
    val monthly: List<MonthStat>,
    val weekday: List<DayStat>,
    val weekendTrade: WeekendTrade?,
    val highlights: List<RuleCard>,
    val caveats: List<String>,
)

data class HistoryMeta(val years: Double, val from: String, val to: String, val bars: Int, val source: String)

/** tier: STRONG(🟢) / WEAK(🟡) / NOISE(⚪) */
data class MonthStat(
    val month: Int,
    val meanPct: Double,
    val medianPct: Double,
    val winRatePct: Double,
    val sampleYears: Int,
    val worstYearPct: Double,
    val bestYearPct: Double,
    val netAfterCostPct: Double,
    val tier: String,
)

data class DayStat(val weekday: Int, val label: String, val meanPct: Double, val winRatePct: Double, val n: Int)

data class WeekendTrade(val meanPct: Double, val winRatePct: Double, val n: Int, val netAfterCostPct: Double)

/** kind: BUY_MONTH / AVOID_MONTH */
data class RuleCard(val kind: String, val title: String, val detail: String, val month: Int?, val tier: String)

/** 가설 빌더 — 커스텀 윈도우 백테스트 결과. */
data class CustomBacktestResult(
    val market: String,
    val ticker: String,
    val name: String,
    val window: String,
    val meanPct: Double,
    val medianPct: Double,
    val winRatePct: Double,
    val sampleYears: Int,
    val worstYearPct: Double,
    val bestYearPct: Double,
    val netAfterCostPct: Double,
    val tier: String,
    val costPct: Double,
    val perYear: List<YearReturn>,
)

data class YearReturn(val year: Int, val returnPct: Double)
