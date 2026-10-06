package com.giwon.signaldesk.features.push.application

import com.giwon.signaldesk.features.market.application.StockQuote
import java.time.Duration
import java.time.Instant

object AlertQuotePolicy {
    fun usable(market: String, quote: StockQuote, now: Instant): Boolean {
        if (!quote.exactPrice.isFinite() || quote.exactPrice <= 0 || !quote.changeRate.isFinite()) return false
        if (market != "US") return true
        val info = quote.quoteInfo ?: return false
        val at = runCatching { Instant.parse(info.observedAt) }.getOrNull() ?: return false
        val age = Duration.between(at, now).seconds
        return info.currency == "USD" && info.session == "OPEN" && info.delayMinutes == 0 && age in 0..300
    }
}
