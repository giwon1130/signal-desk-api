package com.giwon.signaldesk.features.market.application

import java.time.*
import kotlin.math.abs

/** Existing local index feed, with explicit date/reference checks before entering the shared evidence. */
class KoreanIndexEvidence(private val sessions: MarketSessionService) {
    fun convert(quote: IndexQuote, now: Instant): GlobalIndex? {
        val symbol = when (quote.code) { "KOSPI" -> "^KS11"; "KOSDAQ" -> "^KQ11"; else -> return null }
        val observed = runCatching { OffsetDateTime.parse(quote.tradedAt).toInstant() }.getOrNull() ?: return null
        val zone = ZoneId.of("Asia/Seoul")
        val date = observed.atZone(zone).toLocalDate()
        val previous = quote.price - quote.changeAmount
        if (quote.marketStatus !in setOf("OPEN", "CLOSE") || date.year != 2026 || !sessions.isKrTradingDay(date) ||
            observed > now || Duration.between(observed, now).toDays() > 7 ||
            !quote.price.isFinite() || quote.price <= 0 || !previous.isFinite() || previous <= 0 || !quote.changeRate.isFinite() ||
            abs((quote.price / previous - 1) * 100 - quote.changeRate) >= .02) return null
        var previousDate = date.minusDays(1)
        while (!sessions.isKrTradingDay(previousDate)) previousDate = previousDate.minusDays(1)
        return GlobalIndex(quote.name, quote.price, quote.changeRate, symbol, observed.toString(), "NAVER:${quote.code}",
            previous, date.toString(), previousDate.toString())
    }
}
