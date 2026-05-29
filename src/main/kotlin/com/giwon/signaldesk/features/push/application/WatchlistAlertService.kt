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
    private val alertPreferenceService: AlertPreferenceService,
    private val quoteClient: NaverFinanceQuoteClient,
    private val globalQuoteClient: NaverGlobalQuoteClient,
    private val yahooFinanceScreenerClient: com.giwon.signaldesk.features.market.application.YahooFinanceScreenerClient,
    private val chartClient: NaverStockChartClient,
    private val technicalCalculator: TechnicalIndicatorCalculator,
    private val detector: WatchlistAlertDetector,
    private val expoPushClient: ExpoPushClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val krwFmt = NumberFormat.getNumberInstance(Locale.KOREA)

    fun scanAndNotify(market: String = "KR") {
        val allDevices = pushRepository.listAllDevicesGroupedByUser()
        if (allDevices.isEmpty()) return

        // 해당 마켓 알림이 ON된 사용자만 발송 대상
        val enabledUsers = alertPreferenceService.loadEnabledUsers(market)
        val devicesByUser = allDevices.filterKeys { it in enabledUsers }
        if (devicesByUser.isEmpty()) return

        val watchRows = loadWatchRowsFor(devicesByUser.keys, market)
        if (watchRows.isEmpty()) return

        val tickers = watchRows.map { it.ticker }.toSet()
        val quotes: Map<String, StockQuote> = when (market) {
            "US" -> globalQuoteClient.fetchUsQuotes(tickers)
            else -> quoteClient.fetchKoreanQuotes(tickers)
        }
        if (quotes.isEmpty()) return

        // 거래량 급증 비율 산출 — 시장별로 데이터 소스 다름.
        //   KR: Naver 차트 API의 일봉으로 평균 대비 ratio 계산.
        //   US: Yahoo Finance most_actives top 30에 들어왔으면 그 날 거래량 폭증 종목 → volume/avgVolume 비율 사용.
        val volumeRatioByTicker: Map<String, Double> = when (market) {
            "KR" -> {
                val volumeAlertTickers = watchRows.filter { it.volumeAlert }.map { it.ticker }.toSet()
                if (volumeAlertTickers.isNotEmpty()) {
                    volumeAlertTickers.mapNotNull { ticker ->
                        val bars = chartClient.fetchDailyBars(ticker, count = 22)
                        val ratio = technicalCalculator.volumeRatio(bars)
                        if (ratio != null) ticker to ratio else null
                    }.toMap()
                } else emptyMap()
            }
            "US" -> {
                val volumeAlertTickers = watchRows.filter { it.volumeAlert }.map { it.ticker }.toSet()
                if (volumeAlertTickers.isNotEmpty()) {
                    yahooFinanceScreenerClient.fetchMostActives(30)
                        .filter { it.ticker in volumeAlertTickers }
                        .mapNotNull { q ->
                            val avg = q.avgVolume
                            if (avg != null && avg > 0) q.ticker to (q.volume.toDouble() / avg.toDouble()) else null
                        }.toMap()
                } else emptyMap()
            }
            else -> emptyMap()
        }

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
        // 급등/급락 단계적 재알림 — 최근 3일 마지막 알림 강도 대비 +5%p 이상일 때만.
        val recentMaxRate = pushRepository.loadRecentAlertedRates(today.minusDays(2))
        val candidates = detector.detect(refreshed, alreadySent, today, recentMaxRate)
        // 진단(2026-05-29): 알림 스팸 원인 추적 — alreadySent 가 0이면 recordAlert 미작동(근본 버그).
        log.info(
            "alert scan {} — rows={} today={} alreadySentToday={} recentRateKeys={} → candidates={} [{}]",
            market, refreshed.size, today, alreadySent.size, recentMaxRate.size, candidates.size,
            candidates.joinToString { "${it.ticker}/${it.direction}/${"%.1f".format(it.changeRate)}%" },
        )
        if (candidates.isEmpty()) return

        val messages = candidates.flatMap { c ->
            val devices = devicesByUser[c.userId].orEmpty()
            devices.map { d -> buildMessage(d.expoToken, c) }
        }
        expoPushClient.send(messages)
        candidates.forEach { c ->
            pushRepository.recordAlert(c.userId, c.market, c.ticker, c.name, c.direction, today, c.changeRate)
            // 목표가/손절 도달 알림은 1회 발송 후 자동 해제 — 재설정 전까진 재알림 X.
            when (c.direction) {
                AlertDirection.PRICE_ABOVE -> pushRepository.clearPriceAlert(c.userId, c.ticker, clearAbove = true)
                AlertDirection.PRICE_BELOW -> pushRepository.clearPriceAlert(c.userId, c.ticker, clearAbove = false)
                else -> { /* 급등락/거래량은 해제 안 함 */ }
            }
        }
        log.info("Watchlist alert dispatched. candidates={}, messages={}", candidates.size, messages.size)
    }

    private fun buildMessage(token: String, c: AlertCandidate): ExpoPushClient.Message {
        val priceStr = krwFmt.format(c.currentPrice)
        val (title, body) = when (c.direction) {
            AlertDirection.UP -> {
                val signed = String.format("%+.2f%%", c.changeRate)
                "🚀 ${c.name} $signed" to "${priceStr}원 · 단기 급등 — 익절 라인 짚고 가."
            }
            AlertDirection.DOWN -> {
                val signed = String.format("%+.2f%%", c.changeRate)
                "⚠️ ${c.name} $signed" to "${priceStr}원 · 급락 — 손절선 확인하고 추가 매수는 신중하게."
            }
            AlertDirection.PRICE_BELOW -> {
                val threshStr = krwFmt.format(c.thresholdPrice)
                "📉 ${c.name} 손절 도달" to "${priceStr}원 — 설정 손절가 ${threshStr}원 이하. 들고 갈지, 빠질지 지금 결정."
            }
            AlertDirection.PRICE_ABOVE -> {
                val threshStr = krwFmt.format(c.thresholdPrice)
                "🎯 ${c.name} 목표 도달" to "${priceStr}원 — 설정 목표가 ${threshStr}원 돌파. 일부 익절 검토."
            }
            AlertDirection.VOLUME_SPIKE -> {
                val ratioStr = c.volumeRatio?.let { String.format("%.1f", it) } ?: "?"
                "🔥 ${c.name} 거래량 ${ratioStr}배" to "${priceStr}원 · 이상 거래 — 뉴스/공시 1분만 확인."
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
        val userArgs = userIds.map { it.toString() }.toTypedArray()

        // 1) signal_desk_watchlist
        val watchRows = jdbcTemplate.query(
            """
            select user_id, market, ticker, name, change_rate, alert_below, alert_above, volume_alert
            from signal_desk_watchlist
            where market = ? and user_id in ($placeholders)
            """.trimIndent(),
            { rs, _ ->
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
            }, market, *userArgs,
        )

        // 2) signal_desk_portfolio_positions: target_price/stop_loss_price를 알림 트리거로
        //    같은 (user_id, market, ticker)가 watchlist에 있으면 portfolio 값으로 덮어쓴다 (보유가 더 강한 의도).
        val portfolioRows = jdbcTemplate.query(
            """
            select user_id, market, ticker, name, current_price, target_price, stop_loss_price
            from signal_desk_portfolio_positions
            where market = ? and user_id in ($placeholders)
              and (target_price is not null or stop_loss_price is not null)
            """.trimIndent(),
            { rs, _ ->
                WatchlistAlertDetector.WatchRow(
                    userId = UUID.fromString(rs.getString("user_id")),
                    market = rs.getString("market"),
                    ticker = rs.getString("ticker"),
                    name = rs.getString("name"),
                    changeRate = 0.0,
                    alertBelow = rs.getObject("stop_loss_price") as Int?,
                    alertAbove = rs.getObject("target_price") as Int?,
                    volumeAlert = false,
                )
            }, market, *userArgs,
        )

        val byKey = LinkedHashMap<Triple<UUID, String, String>, WatchlistAlertDetector.WatchRow>()
        watchRows.forEach { byKey[Triple(it.userId, it.market, it.ticker)] = it }
        portfolioRows.forEach { p ->
            val k = Triple(p.userId, p.market, p.ticker)
            val existing = byKey[k]
            byKey[k] = if (existing != null) {
                existing.copy(
                    alertBelow = p.alertBelow ?: existing.alertBelow,
                    alertAbove = p.alertAbove ?: existing.alertAbove,
                )
            } else p
        }
        return byKey.values.toList()
    }
}
