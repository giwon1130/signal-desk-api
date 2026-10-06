package com.giwon.signaldesk.features.events.application

import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * 정적 큐레이션 + 휴장일 자동 생성 + Finnhub 빅테크 실적으로 시장 이벤트를 노출.
 *
 * MVP 단계 — 외부 데이터 소스(Trading Economics, Investing.com 등)는 사용자 검증 후 도입.
 * FOMC/CPI/PCE/잭슨홀 같은 정적 이벤트는 [MarketStaticEvents.ALL] 을 수동 갱신해서 채운다.
 */
@Service
class MarketEventService(
    private val marketSessionService: MarketSessionService,
    private val finnhubClient: FinnhubClient,
) {

    /** 오늘 ~ +days 까지의 이벤트. 가까운 날짜 순으로 정렬. */
    fun upcoming(days: Int = 14, watchedUsTickers: Set<String> = emptySet()): List<MarketEvent> {
        return upcomingSnapshot(days, watchedUsTickers).events
    }

    fun upcomingSnapshot(days: Int = 14, watchedUsTickers: Set<String> = emptySet()): MarketEventSnapshot {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val until = today.plusDays(days.coerceIn(1, 60).toLong())

        val holidays = generateHolidays(today, until)
        val statics = MarketStaticEvents.ALL.filter {
            val d = LocalDate.parse(it.date)
            !d.isBefore(today) && !d.isAfter(until)
        }
        val (earnings, status) = fetchEarnings(until, watchedUsTickers)

        return MarketEventSnapshot((holidays + statics + earnings)
            .sortedWith(compareBy({ it.date }, { it.time ?: "" }, { it.title })), status)
    }

    /**
     * Shared calendar -> major cross-sector universe + this user's US watchlist/portfolio.
     * Coverage state belongs to this exact response, not another concurrent user's request.
     */
    private fun fetchEarnings(until: LocalDate, watched: Set<String>): Pair<List<MarketEvent>, EarningsCalendarResult> {
        val tickers = setOf("NVDA", "MSFT", "AAPL", "AMZN", "TSLA", "META", "GOOGL", "AMD", "AVGO", "MU", "TSM",
            "JPM", "BAC", "GS", "V", "MA", "UNH", "LLY", "JNJ", "XOM", "CVX", "WMT", "COST", "NFLX", "ORCL") + watched
        val fromStr = LocalDate.now(ZoneId.of("America/New_York")).toString()
        val toStr = until.toString()
        // One shared calendar fetch, never a separate paid-source request per user/ticker.
        val calendar = finnhubClient.fetchCalendar(fromStr, toStr)
        val events = calendar.entries.filter { e ->
            tickers.any { it.replace('.', '-') == e.symbol.replace('.', '-') }
        }.distinctBy { "${it.symbol}-${it.date}" }
            .map { e ->
                MarketEvent(
                    id = "us-earnings-${e.symbol}-${e.date}",
                    date = e.date,
                    dateTimezone = "America/New_York",
                    sourceUrl = "https://finnhub.io/",
                    time = when (e.hour) {
                        "bmo" -> "장 시작 전 (ET)"
                        "amc" -> "장 마감 후 (ET)"
                        "dmh" -> "장중 (ET)"
                        else -> null
                    },
                    market = "US",
                    category = EventCategory.EARNINGS,
                    title = "${e.symbol} 실적 발표" + if (e.quarter in 1..4) " (Q${e.quarter})" else "",
                    description = "미 동부 날짜 기준 · 발표 시각은 변경될 수 있습니다. 수치의 통화·회계 기준은 기업 발표 원문을 확인해 주세요.",
                    earnings = EarningsFigures(e.epsEstimate, e.epsActual, e.revenueEstimate, e.revenueActual),
                    importance = Importance.HIGH,
                    tickers = listOf(e.symbol),
                )
            }
        return events to calendar.copy(entries = emptyList())
    }

    fun earningsStatus(): EarningsCalendarResult = finnhubClient.status()

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
                    dateTimezone = "America/New_York",
                    sourceUrl = "https://www.nyse.com/trade/hours-calendars",
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

}

data class MarketEventSnapshot(val events: List<MarketEvent>, val earningsStatus: EarningsCalendarResult)
