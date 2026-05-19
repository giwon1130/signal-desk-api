package com.giwon.signaldesk.features.push.application

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.StockQuote
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class PremarketAlertService(
    private val jdbc: JdbcTemplate,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val quoteClient: NaverFinanceQuoteClient,
    private val expoPushClient: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runPremarketAlert(label: String) {
        val allDevices = pushRepository.listAllDevicesGroupedByUser()
        if (allDevices.isEmpty()) return

        val enabledUsers = alertPreferenceService.loadEnabledUsers(market = "KR", includePremarket = true)
        val devicesByUser = allDevices.filterKeys { it in enabledUsers }
        if (devicesByUser.isEmpty()) return

        // 모든 사용자 티커 + 시총 상위를 한 번에 가져와서 사용자별 메시지에서 재사용
        val (watchByUser, portfolioByUser) = loadKrTickers(devicesByUser.keys)
        val allTickers = (watchByUser.values.flatten() + portfolioByUser.values.flatten() + MAJOR_TICKERS).toSet()
        if (allTickers.isEmpty()) return
        val quotes = quoteClient.fetchKoreanQuotes(allTickers)
        if (quotes.isEmpty()) return

        var sent = 0
        for ((userId, devices) in devicesByUser) {
            val watch = watchByUser[userId].orEmpty()
            val portfolio = portfolioByUser[userId].orEmpty()
            val (title, body) = buildMessage(label, watch, portfolio, quotes) ?: continue
            val pushMessages = devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf("type" to "PREMARKET", "label" to label),
                )
            }
            expoPushClient.send(pushMessages)
            sent += pushMessages.size
        }
        log.info("Premarket alert dispatched. label={}, messages={}", label, sent)
    }

    private fun loadKrTickers(userIds: Collection<UUID>): Pair<Map<UUID, List<String>>, Map<UUID, List<String>>> {
        if (userIds.isEmpty()) return emptyMap<UUID, List<String>>() to emptyMap()
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        val params = userIds.map { it.toString() }.toTypedArray()

        val watchByUser = jdbc.query(
            "select user_id, ticker from signal_desk_watchlist where market = 'KR' and user_id in ($placeholders)",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
            *params,
        ).groupBy({ it.first }, { it.second })

        val portfolioByUser = jdbc.query(
            "select user_id, ticker from signal_desk_portfolio_positions where market = 'KR' and user_id in ($placeholders)",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
            *params,
        ).groupBy({ it.first }, { it.second })

        return watchByUser to portfolioByUser
    }

    private fun buildMessage(
        label: String,
        watchTickers: List<String>,
        portfolioTickers: List<String>,
        quotes: Map<String, StockQuote>,
    ): Pair<String, String>? {
        val watchQuotes = watchTickers.mapNotNull { quotes[it] }
        val portfolioQuotes = portfolioTickers.mapNotNull { quotes[it] }
        val majorQuotes = MAJOR_TICKERS.mapNotNull { quotes[it] }
        if (watchQuotes.isEmpty() && portfolioQuotes.isEmpty() && majorQuotes.isEmpty()) return null

        // 톤 결정: 보유 + 관심 평균 (시총상위는 참고용, 톤 결정에서 빼서 개인화)
        val personal = portfolioQuotes + watchQuotes
        val personalAvg = if (personal.isEmpty()) 0.0 else personal.map { it.changeRate }.average()
        val emoji = when {
            personalAvg >= 0.3 -> "🟢"
            personalAvg <= -0.3 -> "🔴"
            else -> "🟡"
        }
        val title = "$emoji 프리마켓 $label · ${signed(personalAvg)}"

        // 본문 우선순위: 보유 → 관심 → 시총상위 → 최대 변동 종목 한 줄
        val parts = buildList {
            if (portfolioQuotes.isNotEmpty()) add(compactPart("보유", portfolioQuotes))
            if (watchQuotes.isNotEmpty()) add(compactPart("관심", watchQuotes))
            if (majorQuotes.isNotEmpty()) add(compactPart("대표", majorQuotes))
        }
        val mover = (watchQuotes + portfolioQuotes + majorQuotes).maxByOrNull { kotlin.math.abs(it.changeRate) }
        val moverLine = mover?.let { "\n📊 ${MAJOR_NAMES[it.ticker] ?: it.ticker} ${signed(it.changeRate)}" } ?: ""

        val body = parts.joinToString(" · ") + moverLine
        return title to body
    }

    /** "보유 +0.85% (↑2)" — 4글자 라벨 + 평균 + 상승 카운트만, 푸시 한 줄 압축. */
    private fun compactPart(label: String, quotes: List<StockQuote>): String {
        if (quotes.isEmpty()) return ""
        val avg = quotes.map { it.changeRate }.average()
        val up = quotes.count { it.changeRate > 0 }
        return "$label ${signed(avg)}(↑$up/${quotes.size})"
    }

    private fun signed(value: Double): String =
        if (value >= 0) "+${"%.2f".format(value)}%" else "${"%.2f".format(value)}%"

    companion object {
        // KOSPI 시총 상위 5개 (2026-05 기준, 변동 시 갱신)
        val MAJOR_NAMES = linkedMapOf(
            "005930" to "삼성전자",
            "000660" to "SK하이닉스",
            "207940" to "삼성바이오로직스",
            "373220" to "LG에너지솔루션",
            "005380" to "현대차",
        )
        val MAJOR_TICKERS: List<String> = MAJOR_NAMES.keys.toList()
    }
}
