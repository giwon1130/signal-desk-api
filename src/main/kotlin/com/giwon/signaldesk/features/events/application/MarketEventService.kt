package com.giwon.signaldesk.features.events.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * 정적 큐레이션 + 휴장일 자동 생성으로 시장 이벤트를 노출.
 *
 * MVP 단계 — 외부 데이터 소스(Trading Economics, Investing.com 등)는 사용자 검증 후 도입.
 * FOMC/실적/공시 같은 정적 이벤트는 STATIC_EVENTS 리스트를 수동 갱신해서 채운다.
 */
@Service
class MarketEventService(
    private val marketSessionService: MarketSessionService,
    private val finnhubClient: FinnhubClient,
) {

    /** 오늘 ~ +days 까지의 이벤트. 가까운 날짜 순으로 정렬. */
    fun upcoming(days: Int = 14): List<MarketEvent> {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val until = today.plusDays(days.coerceIn(1, 60).toLong())

        val holidays = generateHolidays(today, until)
        val statics = STATIC_EVENTS.filter {
            val d = LocalDate.parse(it.date)
            !d.isBefore(today) && !d.isAfter(until)
        }
        val earnings = fetchBigtechEarnings(today, until)

        return (holidays + statics + earnings)
            .sortedWith(compareBy({ it.date }, { it.time ?: "" }, { it.title }))
    }

    /**
     * Finnhub 무료 API로 빅테크 7종(NVDA/MSFT/AAPL/AMZN/TSLA/META/GOOGL)의 실적 발표 일정을 가져온다.
     * FINNHUB_API_KEY env 가 비어있으면 client 가 빈 리스트 반환 → no-op.
     * 향후 확장: 사용자별 watchlist/portfolio US 종목으로 동적 확장 가능.
     */
    private fun fetchBigtechEarnings(from: LocalDate, until: LocalDate): List<MarketEvent> {
        val tickers = listOf("NVDA", "MSFT", "AAPL", "AMZN", "TSLA", "META", "GOOGL")
        val fromStr = from.toString()
        val toStr = until.toString()
        return tickers.flatMap { ticker ->
            finnhubClient.fetchEarningsCalendar(fromStr, toStr, ticker)
        }.distinctBy { "${it.symbol}-${it.date}" }
            .map { e ->
                MarketEvent(
                    id = "us-earnings-${e.symbol}-${e.date}",
                    date = e.date,
                    time = when (e.hour) {
                        "bmo" -> "장 시작 전 (ET)"
                        "amc" -> "장 마감 후 (ET)"
                        "dmh" -> "장중 (ET)"
                        else -> null
                    },
                    market = "US",
                    category = EventCategory.EARNINGS,
                    title = "${e.symbol} 실적 발표 (Q${e.quarter})",
                    description = e.epsEstimate?.let { "EPS 컨센서스 ${"%.2f".format(it)}" },
                    importance = Importance.HIGH,
                    tickers = listOf(e.symbol),
                )
            }
    }

    /** 오늘 발생하는 이벤트만. */
    fun today(): List<MarketEvent> {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
        return upcoming(1).filter { it.date == today }
    }

    private fun generateHolidays(from: LocalDate, until: LocalDate): List<MarketEvent> {
        val out = mutableListOf<MarketEvent>()
        var d = from
        while (!d.isAfter(until)) {
            if (!marketSessionService.isKrTradingDay(d) && d.dayOfWeek.value < 6) {
                // 평일인데 KR 비거래일 = 한국 공휴일
                out += MarketEvent(
                    id = "kr-holiday-$d",
                    date = d.toString(),
                    time = null,
                    market = "KR",
                    category = EventCategory.HOLIDAY,
                    title = "한국 증시 휴장",
                    description = marketSessionService.krHolidayName(d),
                    importance = Importance.MEDIUM,
                )
            }
            if (!marketSessionService.isUsTradingDay(d) && d.dayOfWeek.value < 6) {
                out += MarketEvent(
                    id = "us-holiday-$d",
                    date = d.toString(),
                    time = null,
                    market = "US",
                    category = EventCategory.HOLIDAY,
                    title = "미국 증시 휴장",
                    description = marketSessionService.usHolidayName(d),
                    importance = Importance.MEDIUM,
                )
            }
            d = d.plusDays(1)
        }
        return out
    }

    companion object {
        /**
         * 정적 이벤트 — 매 분기 수동 갱신.
         * FOMC 2026: https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm
         * CPI/PCE 2026: https://www.bls.gov/schedule/news_release/cpi.htm / https://www.bea.gov/news/schedule
         * 한국 ECOS/KRX: https://ecos.bok.or.kr / https://open.krx.co.kr
         *
         * 시각은 KST 기준. FOMC 성명 발표는 14:00 ET (DST 적용 시 익일 03:00 KST,
         * 11월 첫째 일요일 DST 해제 후 익일 04:00 KST).
         * CPI/PCE 는 08:30 ET (DST 적용 시 21:30 KST, 해제 후 22:30 KST) 발표.
         */
        private val STATIC_EVENTS: List<MarketEvent> = listOf(
            // ─── FOMC 2026 (Fed 공식 일정) ─────────────────────────────────────
            MarketEvent(
                id = "fomc-2026-06", date = "2026-06-18", time = "03:00 KST",
                market = "US", category = EventCategory.FOMC,
                title = "FOMC 성명 발표 (6월)", description = "연방공개시장위원회 정책금리 결정",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "fomc-2026-07", date = "2026-07-30", time = "03:00 KST",
                market = "US", category = EventCategory.FOMC,
                title = "FOMC 성명 발표 (7월)", description = "연방공개시장위원회 정책금리 결정",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "fomc-2026-09", date = "2026-09-17", time = "03:00 KST",
                market = "US", category = EventCategory.FOMC,
                title = "FOMC 성명 발표 (9월)", description = "정책금리 결정 + SEP 점도표 공개",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "fomc-2026-10", date = "2026-10-29", time = "03:00 KST",
                market = "US", category = EventCategory.FOMC,
                title = "FOMC 성명 발표 (10월)", description = "연방공개시장위원회 정책금리 결정",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "fomc-2026-12", date = "2026-12-17", time = "04:00 KST",
                market = "US", category = EventCategory.FOMC,
                title = "FOMC 성명 발표 (12월)", description = "정책금리 결정 + SEP 점도표 공개",
                importance = Importance.HIGH,
            ),

            // ─── CPI 2026 — BLS 발표일 (월 평균 13일 부근, 21:30~22:30 KST) ─────
            // 정확한 일자는 BLS 스케줄로 매 분기 검증 필요.
            MarketEvent(
                id = "cpi-2026-05", date = "2026-06-11", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 5월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "cpi-2026-06", date = "2026-07-15", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 6월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "cpi-2026-07", date = "2026-08-12", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 7월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "cpi-2026-08", date = "2026-09-10", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 8월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "cpi-2026-09", date = "2026-10-15", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 9월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "cpi-2026-10", date = "2026-11-12", time = "22:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 10월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "cpi-2026-11", date = "2026-12-10", time = "22:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 11월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
                importance = Importance.HIGH,
            ),

            // ─── PCE 2026 — BEA Personal Income & Outlays (월 말 발표) ──────────
            MarketEvent(
                id = "pce-2026-04", date = "2026-05-29", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 4월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "pce-2026-05", date = "2026-06-26", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 5월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "pce-2026-06", date = "2026-07-31", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 6월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "pce-2026-07", date = "2026-08-28", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 7월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "pce-2026-08", date = "2026-09-25", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 8월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "pce-2026-09", date = "2026-10-30", time = "21:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 9월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
                importance = Importance.HIGH,
            ),
            MarketEvent(
                id = "pce-2026-10", date = "2026-11-25", time = "22:30 KST",
                market = "US", category = EventCategory.ECONOMIC_DATA,
                title = "미국 10월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
                importance = Importance.HIGH,
            ),

            // ─── Jackson Hole 심포지엄 (매년 8월 말, Fed 의장 연설 주목) ────────
            MarketEvent(
                id = "jackson-hole-2026", date = "2026-08-20", time = null,
                market = "US", category = EventCategory.POLICY,
                title = "잭슨홀 경제정책 심포지엄 시작", description = "캔자스시티 연은 주최 — 의장 연설 등 통화정책 시그널 주목",
                importance = Importance.HIGH,
            ),
        )
    }
}
