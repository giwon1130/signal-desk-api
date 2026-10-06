package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.features.market.application.*
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

data class SnapshotClose(val price: BigDecimal, val source: String)

/** Dated provider daily closes only. Never substitute the live quote or a stored portfolio price. */
@Component
class SnapshotClosePrices(
    private val koreanIndices: NaverIndexChartClient,
    private val koreanStocks: NaverStockChartClient,
    private val usCandles: YahooCandleClient,
) {
    fun indices(session: SnapshotSession): Map<String, SnapshotClose> =
        (if (session.market == "KR") listOf("KOSPI", "KOSDAQ") else listOf("^IXIC", "^GSPC"))
            .mapNotNull { symbol ->
                val rows = runCatching {
                    if (session.market == "KR") koreanIndices.fetchOhlc(symbol, NaverIndexChartClient.PeriodType.DAILY, 5)
                    else usCandles.fetch(symbol, "6mo")
                }.getOrDefault(emptyList())
                select(rows, session)?.let { symbol to it }
            }.toMap()

    fun stock(ticker: String, session: SnapshotSession): SnapshotClose? {
        val valid = if (session.market == "KR") ticker.matches(Regex("\\d{6}"))
            else ticker.matches(Regex("[A-Z0-9][A-Z0-9.-]{0,14}"))
        if (!valid) return null
        return runCatching {
            val rows = if (session.market == "US") usCandles.fetch(ticker.replace('.', '-'), "6mo")
            else koreanStocks.fetchDailyOhlc(ticker, 5).map { IndexCandle(it.date, it.open, it.high, it.low, it.close, 0) }
            select(rows, session)
        }.getOrNull()
    }

    internal fun select(rows: List<IndexCandle>, session: SnapshotSession): SnapshotClose? {
        val date = session.date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val row = rows.singleOrNull { it.date == date } ?: return null
        if (row.provisional || listOf(row.open, row.high, row.low, row.close).any { !it.isFinite() || it <= 0 } ||
            row.high < maxOf(row.open, row.close) || row.low > minOf(row.open, row.close)) return null
        return SnapshotClose(BigDecimal.valueOf(row.close), if (session.market == "KR") "NAVER_FINANCE" else "YAHOO_FINANCE")
    }
}
