package com.giwon.signaldesk.features.push.application

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import com.giwon.signaldesk.features.market.application.NaverStockChartClient
import com.giwon.signaldesk.features.market.application.StockQuote
import com.giwon.signaldesk.features.market.application.TechnicalIndicatorCalculator
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * 관심종목 알림 감시 + 푸시 발송 orchestration.
 *
 * 감지 항목:
 *   - ±5% 급등락 (기존)
 *   - 현재가 ≤ alertBelow → 매수 타이밍 알림
 *   - 현재가 ≥ alertAbove → 상한 도달 알림
 *   - volumeRatio ≥ 3.0 AND volumeAlert=true → 거래량 급증 알림
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class WatchlistAlertService(
    private val jdbcTemplate: JdbcTemplate,
    private val pushRepository: PushRepository,
    private val quoteClient: NaverFinanceQuoteClient,
    private val globalQuoteClient: NaverGlobalQuoteClient,
    private val chartClient: NaverStockChartClient,
    private val technicalCalculator: TechnicalIndicatorCalculator,
    private val detector: WatchlistAlertDetector,
    private val expoPushClient: ExpoPushClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val krwFmt = NumberFormat.getNumberInstance(Locale.KOREA)

    fun scanAndNotify(market: String = "KR") {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return

        val watchRows = loadWatchRowsFor(devicesByUser.keys, market)
        if (watchRows.isEmpty()) return

        val tickers = watchRows.map { it.ticker }.toSet()
        val quotes: Map<String, StockQuote> = when (market) {
            "US" -> globalQuoteClient.fetchUsQuotes(tickers)
            else -> quoteClient.fetchKoreanQuotes(tickers)
        }
        if (quotes.isEmpty()) return

        // 거래량 급증 알림은 한국 차트 API에만 의존 — US는 가격 알림만.
        val volumeRatioByTicker: Map<String, Double> = if (market == "KR") {
            val volumeAlertTickers = watchRows.filter { it.volumeAlert }.map { it.ticker }.toSet()
            if (volumeAlertTickers.isNotEmpty()) {
                volumeAlertTickers.mapNotNull { ticker ->
                    val bars = chartClient.fetchDailyBars(ticker, count = 22)
                    val ratio = technicalCalculator.volumeRatio(bars)
                    if (ratio != null) ticker to ratio else null
                }.toMap()
            } else emptyMap()
        } else emptyMap()

        val refreshed = watchRows.mapNotNull { r ->
            val q = quotes[r.ticker] ?: return@mapNotNull null
            r.copy(
                changeRate = q.changeRate,
                currentPrice = q.currentPrice,
                volumeRatio = volumeRatioByTicker[r.ticker],
            )
        }

        val today = LocalDate.now(clock)
        val alreadySent = pushRepository.loadRecentAlertLog(today)
        val candidates = detector.detect(refreshed, alreadySent, today)
        if (candidates.isEmpty()) return

        val messages = candidates.flatMap { c ->
            val devices = devicesByUser[c.userId].orEmpty()
            devices.map { d -> buildMessage(d.expoToken, c) }
        }
        expoPushClient.send(messages)
        candidates.forEach { c ->
            pushRepository.recordAlert(c.userId, c.market, c.ticker, c.name, c.direction, today, c.changeRate)
        }
        log.info("Watchlist alert dispatched. candidates={}, messages={}", candidates.size, messages.size)
    }

    private fun buildMessage(token: String, c: AlertCandidate): ExpoPushClient.Message {
        val (title, body) = when (c.direction) {
            AlertDirection.UP -> {
                val signed = String.format("%+.2f%%", c.changeRate)
                "↑ ${c.name} $signed" to "급등 감지 — 지금 확인해보세요."
            }
            AlertDirection.DOWN -> {
                val signed = String.format("%+.2f%%", c.changeRate)
                "↓ ${c.name} $signed" to "급락 감지 — 지금 확인해보세요."
            }
            AlertDirection.PRICE_BELOW -> {
                val priceStr = krwFmt.format(c.currentPrice)
                val threshStr = krwFmt.format(c.thresholdPrice)
                "📉 ${c.name} ${priceStr}원 도달" to "설정한 하한가(${threshStr}원) 이하로 떨어졌어. 매수 타이밍을 확인해봐."
            }
            AlertDirection.PRICE_ABOVE -> {
                val priceStr = krwFmt.format(c.currentPrice)
                val threshStr = krwFmt.format(c.thresholdPrice)
                "📈 ${c.name} ${priceStr}원 돌파" to "설정한 상한가(${threshStr}원) 이상으로 올랐어. 지금 확인해봐."
            }
            AlertDirection.VOLUME_SPIKE -> {
                val ratioStr = c.volumeRatio?.let { String.format("%.1f", it) } ?: "?"
                "🔥 ${c.name} 거래량 급증" to "평균 대비 ${ratioStr}배 거래량이 터졌어. 이유를 확인해봐."
            }
        }
        return ExpoPushClient.Message(
            to = token,
            title = title,
            body = body,
            data = mapOf("ticker" to c.ticker, "market" to c.market, "direction" to c.direction.name),
        )
    }

    private fun loadWatchRowsFor(userIds: Collection<UUID>, market: String): List<WatchlistAlertDetector.WatchRow> {
        if (userIds.isEmpty()) return emptyList()
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        val sql = """
            select user_id, market, ticker, name, change_rate, alert_below, alert_above, volume_alert
            from signal_desk_watchlist
            where market = ? and user_id in ($placeholders)
        """.trimIndent()
        return jdbcTemplate.query(sql, { rs, _ ->
            WatchlistAlertDetector.WatchRow(
                userId = UUID.fromString(rs.getString("user_id")),
                market = rs.getString("market"),
                ticker = rs.getString("ticker"),
                name = rs.getString("name"),
                changeRate = rs.getDouble("change_rate"),
                alertBelow = rs.getObject("alert_below") as Int?,
                alertAbove = rs.getObject("alert_above") as Int?,
                volumeAlert = rs.getBoolean("volume_alert"),
            )
        }, market, *userIds.map { it.toString() }.toTypedArray())
    }
}
