package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 합성 위험도 산출기.
 *
 * PizzINT 실험 지표 3종은 같은 소스라 단독으로는 노이즈가 크고, VIX/뉴스도 따로 보면 해석이 갈린다.
 * → 세 신호를 VIX 중심 가중(0.5 / 0.3 / 0.2)으로 합쳐 "오늘 시장이 얼마나 불안정한가"를
 *   1~10 단일 위험도로 환산한다. UI 에는 이 합성 결과만 노출한다.
 */
@Service
class CompositeRiskService {

    /** 통합 위험도(기존) — 美 VIX·한국 지수·PizzINT·뉴스 가중 합성. 호환/폴백용으로 유지. */
    fun build(
        alternativeSignals: List<AlternativeSignal>,
        vix: VixSnapshot?,
        news: List<MarketNews>,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
        koreaMarket: MarketSection? = null,
        usdKrw: FredSeriesSnapshot? = null,
        us10y: FredSeriesSnapshot? = null,
    ): CompositeRiskSignal = assemble(
        components = listOf(
            vixComponent(vix, 0.22),
            krComponent(koreaMarket, 0.22),
            fxComponent(usdKrw, 0.15),
            rateComponent(us10y, 0.13),
            pizzComponent(alternativeSignals, 0.13),
            newsComponent(news, 0.15),
        ),
        marketLabel = "",
        watchlist = watchlist,
        portfolio = portfolio,
    )

    /** 한국 투자자 관점 — 한국 지수(0.45) + 원/달러 환율(0.25) + 한국 뉴스(0.30). */
    fun buildKr(
        koreaMarket: MarketSection?,
        news: List<MarketNews>,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
        usdKrw: FredSeriesSnapshot? = null,
    ): CompositeRiskSignal = assemble(
        components = listOf(
            krComponent(koreaMarket, 0.45),
            fxComponent(usdKrw, 0.25),
            newsComponent(news.filter { it.market.equals("KR", ignoreCase = true) }, 0.30),
        ),
        marketLabel = "한국 ",
        watchlist = watchlist,
        portfolio = portfolio,
    )

    /** 미국 투자자 관점 — 美 VIX(0.40) + 미 10년물 금리(0.20) + 미국 뉴스(0.25) + 지정학 PizzINT(0.15). */
    fun buildUs(
        vix: VixSnapshot?,
        alternativeSignals: List<AlternativeSignal>,
        news: List<MarketNews>,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
        us10y: FredSeriesSnapshot? = null,
    ): CompositeRiskSignal = assemble(
        components = listOf(
            vixComponent(vix, 0.40),
            rateComponent(us10y, 0.20),
            newsComponent(news.filter { it.market.equals("US", ignoreCase = true) }, 0.25),
            pizzComponent(alternativeSignals, 0.15),
        ),
        marketLabel = "미국 ",
        watchlist = watchlist,
        portfolio = portfolio,
    )

    /** 컴포넌트 목록 → 1~10 위험도 신호로 환산 (통합/시장별 공통). */
    private fun assemble(
        components: List<RiskComponent>,
        marketLabel: String,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
    ): CompositeRiskSignal {
        val score100 = components.sumOf { it.score * it.weight }.roundToInt().coerceIn(0, 100)
        val score = (score100 / 10.0).roundToInt().coerceIn(1, 10)
        val level = levelOf(score)
        return CompositeRiskSignal(
            score = score,
            score100 = score100,
            level = level,
            headline = buildHeadline(score, level, marketLabel, components),
            components = components,
            description = DESCRIPTION,
            methodology = METHODOLOGY,
            asOf = LocalDateTime.now().toString(),
            personalImpact = buildPersonalImpact(score, watchlist, portfolio),
        )
    }

    // ─── 美 VIX 변동성 (가중 0.3) ─────────────────────────────────────────────
    private fun vixComponent(vix: VixSnapshot?, weight: Double): RiskComponent {
        if (vix == null) {
            return RiskComponent(
                label = "VIX 변동성", score = 45, weight = weight,
                state = "데이터 대기", detail = "CBOE VIX 응답 없음 — 중립값으로 처리",
            )
        }
        val raw = (vix.currentPrice - VIX_CALM) / (VIX_PANIC - VIX_CALM) * 100
        // 전일 대비 상승 추세면 위험 가산, 하락이면 소폭 차감.
        val trendBonus = (vix.priceChange * 2.0).coerceIn(-8.0, 12.0)
        val score = (raw + trendBonus).roundToInt().coerceIn(0, 100)
        val changeText = if (vix.priceChange >= 0) "+${"%.2f".format(vix.priceChange)}" else "%.2f".format(vix.priceChange)
        return RiskComponent(
            label = "VIX 변동성",
            score = score,
            weight = weight,
            state = when {
                score >= 70 -> "변동성 경계"
                score >= 40 -> "보통"
                else -> "안정"
            },
            detail = "VIX ${"%.1f".format(vix.currentPrice)} (전일 대비 $changeText)",
        )
    }

    // ─── 한국 지수 변동 (가중 0.3) — 한국 시장 급변동을 위험도에 반영 ──────────
    // VIX(미국)만으론 한국 사이드카·급락 같은 KR 변동성이 0으로 잡혀 "안정"으로 오인됐다.
    private fun krComponent(koreaMarket: MarketSection?, weight: Double): RiskComponent {
        val indices = koreaMarket?.indices.orEmpty()
        if (indices.isEmpty()) {
            return RiskComponent(
                label = "한국 지수 변동", score = 45, weight = weight,
                state = "데이터 대기", detail = "KR 지수 응답 없음 — 중립값으로 처리",
            )
        }
        // 하락은 위험으로 크게(낙폭×32), 상승은 변동성으로 작게(×10). 가장 불안한 지수가 분위기를 끈다.
        val score = indices.maxOf { idx ->
            val ch = idx.changeRate
            (if (ch < 0) -ch * KR_DOWN_SLOPE else ch * KR_UP_SLOPE).coerceIn(0.0, 100.0)
        }.roundToInt().coerceIn(0, 100)
        return RiskComponent(
            label = "한국 지수 변동",
            score = score,
            weight = weight,
            state = when {
                score >= 70 -> "급변동 경계"
                score >= 40 -> "변동 확대"
                else -> "안정"
            },
            detail = indices.joinToString(" · ") { "${it.label} ${"%+.2f".format(it.changeRate)}%" },
        )
    }

    // ─── 원/달러 환율 (한국 관점) ────────────────────────────────────────────
    // 원화 급약세(환율 급등)·고환율은 외국인 자금 이탈·수입물가 부담으로 한국 시장 위험을 키운다.
    private fun fxComponent(usdKrw: FredSeriesSnapshot?, weight: Double): RiskComponent {
        if (usdKrw == null) {
            return RiskComponent(
                label = "원/달러 환율", score = 45, weight = weight,
                state = "데이터 대기", detail = "환율 응답 없음 — 중립값으로 처리",
            )
        }
        val ch = usdKrw.changeRate          // 전일 대비 %, +면 원화 약세
        val level = usdKrw.currentValue     // 원
        val changeRisk = (ch * 22.0).coerceIn(-12.0, 70.0)
        val levelRisk = when {
            level >= 1450 -> 35.0
            level >= 1400 -> 22.0
            level >= 1350 -> 10.0
            else -> 0.0
        }
        val score = (changeRisk + levelRisk).roundToInt().coerceIn(0, 100)
        val signed = if (ch >= 0) "+${"%.2f".format(ch)}" else "%.2f".format(ch)
        return RiskComponent(
            label = "원/달러 환율",
            score = score,
            weight = weight,
            state = when {
                score >= 70 -> "원화 급약세 경계"
                score >= 40 -> "원화 약세 부담"
                else -> "안정"
            },
            detail = "환율 ${"%,.0f".format(level)}원 · 전일 $signed%",
        )
    }

    // ─── 미 10년물 금리 (미국 관점) ──────────────────────────────────────────
    // 금리 급등·고금리는 주식 할인율을 끌어올려(특히 성장/기술주) 위험을 키운다.
    private fun rateComponent(us10y: FredSeriesSnapshot?, weight: Double): RiskComponent {
        if (us10y == null) {
            return RiskComponent(
                label = "미 10년물 금리", score = 45, weight = weight,
                state = "데이터 대기", detail = "금리 응답 없음 — 중립값으로 처리",
            )
        }
        val yld = us10y.currentValue        // 수익률 %
        val chPct = us10y.changeRate        // 전일 대비 %
        val prev = if (chPct > -100.0) yld / (1 + chPct / 100.0) else yld
        val bp = (yld - prev) * 100.0       // 전일 대비 bp
        val changeRisk = (bp * 4.0).coerceIn(-12.0, 60.0)
        val levelRisk = when {
            yld >= 5.0 -> 35.0
            yld >= 4.5 -> 22.0
            yld >= 4.0 -> 10.0
            else -> 0.0
        }
        val score = (changeRisk + levelRisk).roundToInt().coerceIn(0, 100)
        val signedBp = if (bp >= 0) "+${"%.1f".format(bp)}" else "%.1f".format(bp)
        return RiskComponent(
            label = "미 10년물 금리",
            score = score,
            weight = weight,
            state = when {
                score >= 70 -> "금리 급등 경계"
                score >= 40 -> "금리 부담"
                else -> "안정"
            },
            detail = "미 10년물 ${"%.2f".format(yld)}% · 전일 ${signedBp}bp",
        )
    }

    // ─── PizzINT 종합 (가중 0.2) ─────────────────────────────────────────────
    private fun pizzComponent(signals: List<AlternativeSignal>, weight: Double): RiskComponent {
        val pizza = scoreOf(signals, "Pentagon Pizza Index")
        val policy = scoreOf(signals, "Policy Buzz")
        val bar = scoreOf(signals, "Bar Counter-Signal")
        // PizzINT 원지표는 floor + sqrt 변환 탓에 평온한 장에도 60 안팎으로 높게 깔린다.
        // 가중 평균(rawBlend)을 [PIZZ_RAW_FLOOR..PIZZ_RAW_CEIL] 기준으로 recenter 해서
        // 평상시 읽기값이 중간값 아래로 오도록 보정한다 (상시 높은 baseline 제거).
        val rawBlend = pizza * 0.5 + policy * 0.3 + bar * 0.2
        val score = ((rawBlend - PIZZ_RAW_FLOOR) / (PIZZ_RAW_CEIL - PIZZ_RAW_FLOOR) * 100)
            .roundToInt().coerceIn(0, 100)
        return RiskComponent(
            label = "PizzINT 종합",
            score = score,
            weight = weight,
            state = when {
                score >= 70 -> "지정학 노이즈 큼"
                score >= 40 -> "보통"
                else -> "잠잠"
            },
            detail = "Pentagon Pizza $pizza · Policy Buzz $policy · Bar Counter $bar (재보정 적용)",
        )
    }

    private fun scoreOf(signals: List<AlternativeSignal>, label: String): Int =
        signals.firstOrNull { it.label == label }?.score ?: 50

    // ─── 뉴스 키워드 빈도 (가중 0.2) ─────────────────────────────────────────
    private fun newsComponent(news: List<MarketNews>, weight: Double): RiskComponent {
        if (news.isEmpty()) {
            return RiskComponent(
                label = "뉴스 키워드", score = 45, weight = weight,
                state = "데이터 대기", detail = "뉴스 표본 없음 — 중립값으로 처리",
            )
        }
        var weightSum = 0.0
        var strongTitles = 0
        var mildTitles = 0
        news.forEach { item ->
            val title = item.title.lowercase()
            when {
                STRONG_RISK_KEYWORDS.any { title.contains(it) } -> { weightSum += 1.0; strongTitles++ }
                MILD_RISK_KEYWORDS.any { title.contains(it) } -> { weightSum += 0.4; mildTitles++ }
            }
        }
        val density = weightSum / news.size
        val score = (density / NEWS_DENSITY_MAX * 100).roundToInt().coerceIn(0, 100)
        return RiskComponent(
            label = "뉴스 키워드",
            score = score,
            weight = weight,
            state = when {
                score >= 70 -> "위험 키워드 집중"
                score >= 40 -> "보통"
                else -> "차분"
            },
            detail = "표본 ${news.size}건 중 위험 키워드 강 ${strongTitles}건 · 약 ${mildTitles}건",
        )
    }

    // ─── 합성 해석 ───────────────────────────────────────────────────────────
    private fun levelOf(score: Int) = when {
        score >= 9 -> "고위험"
        score >= 7 -> "경계"
        score >= 5 -> "주의"
        score >= 3 -> "관망"
        else -> "안정"
    }

    private fun buildHeadline(score: Int, level: String, marketLabel: String, components: List<RiskComponent>): String {
        val driver = components.maxByOrNull { it.score }?.label ?: "복합 요인"
        return when {
            score >= 8 -> "오늘 ${marketLabel}시장 위험도 $score/10 — $level. ${driver}가 가장 크게 자극 중이라 신규 진입은 신중하게 해 주세요."
            score >= 6 -> "오늘 ${marketLabel}시장 위험도 $score/10 — $level. $driver 흐름을 보면서 대응해 주세요."
            score >= 4 -> "오늘 ${marketLabel}시장 위험도 $score/10 — $level. 큰 충격 신호는 없지만 평소 페이스를 유지해 주세요."
            else -> "오늘 ${marketLabel}시장 위험도 $score/10 — $level. 외부 위험 신호가 약한 차분한 구간입니다."
        }
    }

    private fun buildPersonalImpact(
        score: Int,
        watchlist: List<WatchItem>,
        portfolio: PortfolioSummary,
    ): String? {
        val held = portfolio.positions.size
        val watched = watchlist.size
        if (held == 0 && watched == 0) return null
        return when {
            score >= 7 && held > 0 -> "보유 ${held}종목 — 위험도 높음. 손절 기준과 변동성 큰 종목 비중을 먼저 점검해 주세요."
            score >= 7 -> "관심 ${watched}종목 — 위험도 높음. 신규 진입은 지표 진정 후로 미루는 것이 안전합니다."
            held > 0 -> "보유 ${held}종목 · 관심 ${watched}종목 — 위험도 보통 이하. 평소 모니터링 페이스를 유지해 주세요."
            else -> "관심 ${watched}종목 — 위험도 보통 이하. 진입 후보를 차분히 좁혀둘 만한 구간입니다."
        }
    }

    companion object {
        // 한국 지수 일간 변동 → 위험. 하락은 크게(낙폭×32: -3.1%→100), 상승은 작게(×10: 변동성만 반영).
        private const val KR_DOWN_SLOPE = 32.0
        private const val KR_UP_SLOPE = 10.0

        // VIX 정규화 기준: 13 이하 = 위험 0, 36 이상 = 위험 100.
        private const val VIX_CALM = 13.0
        private const val VIX_PANIC = 36.0

        // 뉴스 위험 밀도 정규화 상한 (헤드라인당 가중합 평균). 0.55 이상이면 100점.
        private const val NEWS_DENSITY_MAX = 0.55

        // PizzINT 종합 recenter 기준: 가중평균 raw 48 이하 = 0, 90 이상 = 100.
        // PizzINT 원지표가 평온한 장에도 59~73 수준으로 깔리는 baseline 을 걷어낸다.
        private const val PIZZ_RAW_FLOOR = 48.0
        private const val PIZZ_RAW_CEIL = 90.0

        private val STRONG_RISK_KEYWORDS = listOf(
            "폭락", "급락", "추락", "쇼크", "충격", "패닉", "공포", "위기", "침체", "리세션",
            "전쟁", "분쟁", "제재", "디폴트", "파산", "부도", "서킷브레이커", "사이드카", "하한가", "투매",
            "비상", "폭탄", "위협",
        )
        private val MILD_RISK_KEYWORDS = listOf(
            "하락", "약세", "우려", "불안", "경고", "둔화", "부진", "손실", "적자", "하향",
            "리스크", "긴축", "인플레", "관세", "조정", "매도세", "경계", "변동성", "급등락", "출렁", "낙폭",
        )

        private const val DESCRIPTION =
            "美 VIX 변동성, 한국 지수(KOSPI/KOSDAQ) 일간 변동, PizzINT 실험 지표(Pentagon Pizza/Policy Buzz/" +
            "Bar Counter-Signal), 주요 뉴스 위험 키워드 빈도 — 네 신호를 하나로 합친 1~10 시장 위험도. " +
            "개별 지표는 노이즈가 커서 단독으로 보기 어렵기 때문에, 가중 합성해 '오늘 얼마나 불안정한가'만 본다."
        private const val METHODOLOGY =
            "1) 美 VIX 변동성 (가중 0.3) — VIX 13~36 구간을 0~100으로 정규화, 전일 대비 상승분 가산\n" +
            "2) 한국 지수 변동 (가중 0.3) — KOSPI/KOSDAQ 일간 등락에서 낙폭 중심으로 위험 산출\n" +
            "3) PizzINT 종합 (가중 0.2) — Pentagon Pizza 0.5 / Policy Buzz 0.3 / Bar Counter 0.2 가중 평균 후 baseline 보정\n" +
            "4) 뉴스 키워드 (가중 0.2) — 주요 헤드라인 표본의 위험 키워드 밀도\n" +
            "각 sub-score(0~100)를 가중 합산한 뒤 1~10 위험도로 환산.\n" +
            "※ 한국 관점엔 원/달러 환율(원화 약세·고환율=위험), 미국 관점엔 미 10년물 금리(금리 급등=위험)를 추가 반영."
    }
}
