package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.events.application.FinnhubClient
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.YahooFinanceScreenerClient
import com.giwon.signaldesk.features.market.application.YahooQuote
import com.giwon.signaldesk.features.market.application.UsIndicesSnapshot
import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * US 이브닝 브리프 — NY 장 마감 직후(06:30 KST) NASDAQ/S&P 변동·주도주·실적을 한 줄로 푸시.
 * 현재 template 기반 합성 (follow-up: Gemini 합성 업그레이드).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class EveningBriefService(
    private val fredIndexClient: FredIndexClient,
    private val yahooFinanceScreenerClient: YahooFinanceScreenerClient,
    private val finnhubClient: FinnhubClient,
    private val expoPushClient: ExpoPushClient,
    private val pushRepository: PushRepository,
    private val alertPreferenceService: AlertPreferenceService,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runBrief() {
        val devicesByUser = pushRepository.listAllDevicesGroupedByUser()
        if (devicesByUser.isEmpty()) return
        val enabledUsers = alertPreferenceService.loadEveningBriefEnabledUsers()
        val targets = devicesByUser.filterKeys { it in enabledUsers }
        if (targets.isEmpty()) {
            log.info("Evening brief — no opted-in recipients")
            return
        }

        val indices = fredIndexClient.fetchUsIndices()
        val gainers = yahooFinanceScreenerClient.fetchGainers(3)
        val losers = yahooFinanceScreenerClient.fetchLosers(3)
        val today = LocalDate.now(clock)
        val earnings = finnhubClient.fetchEarningsCalendar(today.minusDays(1).toString(), today.toString())

        val (title, body) = buildPushMessage(indices, gainers, losers, earnings.size)

        val messages = targets.flatMap { (_, devices) ->
            devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = title,
                    body = body,
                    data = mapOf("type" to "EVENING_BRIEF"),
                )
            }
        }
        expoPushClient.send(messages)
        log.info("Evening brief dispatched. recipients={}, messages={}", targets.size, messages.size)
    }

    private fun buildPushMessage(
        indices: UsIndicesSnapshot?,
        gainers: List<YahooQuote>,
        losers: List<YahooQuote>,
        earningsCount: Int,
    ): Pair<String, String> {
        val nasdaqRate = indices?.nasdaq?.changeRate
        val sp500Rate = indices?.sp500?.changeRate
        val emoji = when {
            nasdaqRate != null && nasdaqRate <= -1.0 -> "🔴"
            nasdaqRate != null && nasdaqRate >= 1.0 -> "🟢"
            else -> "🟡"
        }
        val title = "$emoji 미장 이브닝 브리프"

        val parts = mutableListOf<String>()
        if (nasdaqRate != null && sp500Rate != null) {
            parts += "NASDAQ ${formatRate(nasdaqRate)} · S&P ${formatRate(sp500Rate)}"
        }
        val topGain = gainers.firstOrNull()
        val topLoss = losers.firstOrNull()
        if (topGain != null) parts += "🚀${topGain.ticker} ${formatRate(topGain.changeRate)}"
        if (topLoss != null) parts += "⚠️${topLoss.ticker} ${formatRate(topLoss.changeRate)}"
        if (earningsCount > 0) parts += "실적 ${earningsCount}건"

        val body = parts.joinToString(" · ").ifBlank { "장 마감 데이터 확인 중" }.take(180)
        return title to body
    }

    private fun formatRate(r: Double): String = if (r >= 0) "+${"%.2f".format(r)}%" else "${"%.2f".format(r)}%"
}
