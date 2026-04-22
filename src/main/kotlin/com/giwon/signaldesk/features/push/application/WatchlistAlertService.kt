package com.giwon.signaldesk.features.push.application

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 관심종목 급등락 감시 + 푸시 발송을 orchestration.
 *
 * 흐름:
 *   1) 푸시 토큰 보유 유저를 뽑고
 *   2) 그 유저들의 watchlist(KR)를 스캔
 *   3) Naver 실시간 쿼트로 당일 변화율을 갱신
 *   4) WatchlistAlertDetector 가 ±5% 후보를 뽑음
 *   5) alert_log 기준 dedup → ExpoPushClient 로 발송 → 로그 기록
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class WatchlistAlertService(
    private val jdbcTemplate: JdbcTemplate,
    private val pushRepository: PushRepository,
    private val quoteClient: NaverFinanceQuoteClient,
    private val detector: WatchlistAlertDetector,
    private val expoPushClient: ExpoPushClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scanAndNotify() {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return

        val watchRows = loadKrWatchRowsFor(devicesByUser.keys)
        if (watchRows.isEmpty()) return

        val tickers = watchRows.map { it.ticker }.toSet()
        val quotes = quoteClient.fetchKoreanQuotes(tickers)
        if (quotes.isEmpty()) return

        val refreshed = watchRows.mapNotNull { r ->
            val q = quotes[r.ticker] ?: return@mapNotNull null
            r.copy(changeRate = q.changeRate)
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
            pushRepository.recordAlert(c.userId, c.ticker, c.direction, today, c.changeRate)
        }
        log.info("Watchlist alert dispatched. candidates={}, messages={}", candidates.size, messages.size)
    }

    private fun buildMessage(token: String, c: AlertCandidate): ExpoPushClient.Message {
        val arrow = if (c.direction == AlertDirection.UP) "↑" else "↓"
        val signed = String.format("%+.2f%%", c.changeRate)
        return ExpoPushClient.Message(
            to = token,
            title = "$arrow ${c.name} $signed",
            body = "관심종목 급등락 감지 — 지금 확인해보세요.",
            data = mapOf("ticker" to c.ticker, "market" to c.market, "direction" to c.direction.name),
        )
    }

    private fun loadKrWatchRowsFor(userIds: Collection<UUID>): List<WatchlistAlertDetector.WatchRow> {
        if (userIds.isEmpty()) return emptyList()
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        val sql = """
            select user_id, market, ticker, name, change_rate
            from signal_desk_watchlist
            where market = 'KR' and user_id in ($placeholders)
        """.trimIndent()
        return jdbcTemplate.query(sql, { rs, _ ->
            WatchlistAlertDetector.WatchRow(
                userId = UUID.fromString(rs.getString("user_id")),
                market = rs.getString("market"),
                ticker = rs.getString("ticker"),
                name = rs.getString("name"),
                changeRate = rs.getDouble("change_rate"),
            )
        }, *userIds.map { it.toString() }.toTypedArray())
    }
}
