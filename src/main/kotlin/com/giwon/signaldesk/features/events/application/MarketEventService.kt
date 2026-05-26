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
         * FOMC 일정: https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm
         * 한국 ECOS/KRX: https://ecos.bok.or.kr / https://open.krx.co.kr
         */
        private val STATIC_EVENTS: List<MarketEvent> = emptyList()
    }
}
