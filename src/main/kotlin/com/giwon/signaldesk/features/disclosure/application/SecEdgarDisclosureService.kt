package com.giwon.signaldesk.features.disclosure.application

import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import com.giwon.signaldesk.features.workspace.application.UserWatchTickerRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Watched issuers' official submissions: rotating 20-CIK batches, 14-day backfill,
 * 8-K/6-K/quarterly/annual forms including amendments. Alerts remain at-most-once;
 * old backfilled evidence is stored without sending a historical alert burst.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class SecEdgarDisclosureService(
    private val jdbc: JdbcTemplate,
    private val userWatchTickers: UserWatchTickerRepository,
    private val client: SecSubmissionsClient,
    private val tickerRegistry: SecEdgarTickerRegistry,
    private val pushRepo: PushRepository,
    private val alertPrefs: AlertPreferenceService,
    private val expoPushClient: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var scanCursor = 0

    @Synchronized fun runScan(): Int {
        val ciks = userWatchTickers.tickersByUser("US").values.flatten().distinct()
            .mapNotNull(tickerRegistry::resolveCik).distinct().sorted()
        if (ciks.isEmpty()) return 0
        // Rotate bounded batches; backfill 14 days after restarts/outages. Never scan all issuers per user.
        val selected = (0 until minOf(20, ciks.size)).map { ciks[(scanCursor + it) % ciks.size] }
        scanCursor = (scanCursor + selected.size) % ciks.size
        val results = selected.map(client::fetch)
        results.groupingBy { it.status }.eachCount().let { log.info("SEC submissions coverage: {}", it) }
        val recent = results.flatMap { it.filings }.distinctBy { it.accessionNo }
        if (recent.isEmpty()) return 0

        // Dedup against seen table.
        val accNos = recent.map { it.accessionNo }
        val placeholders = accNos.joinToString(",") { "?" }
        val seen = if (accNos.isNotEmpty()) {
            jdbc.query(
                "SELECT accession_no FROM signal_desk_us_disclosure_seen WHERE accession_no IN ($placeholders)",
                { rs, _ -> rs.getString("accession_no") },
                *accNos.toTypedArray(),
            ).toSet()
        } else emptySet()
        val fresh = recent.filter { it.accessionNo !in seen }
        if (fresh.isEmpty()) return 0

        // CIK → ticker. 매칭 안 되는 회사도 있음 (사모/펀드/외국기업) — 그건 push 대상 아님.
        val freshWithTicker = fresh.flatMap { item ->
            tickerRegistry.resolveTickers(item.cik).map { item to it }
        }

        // seen 먼저 기록 후 푸시 — 푸시/크래시가 다음 스캔에서 같은 공시를 중복 발송하지 않게(at-most-once).
        // Mark all fresh as seen (including unmatched).
        jdbc.batchUpdate(
            """
            INSERT INTO signal_desk_us_disclosure_seen (accession_no, cik, ticker, form_type, company_name, filed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (accession_no) DO NOTHING
            """.trimIndent(),
            fresh.map { item ->
                arrayOf<Any?>(
                    item.accessionNo,
                    item.cik,
                    tickerRegistry.resolveTicker(item.cik),
                    item.formType,
                    item.companyName.take(255),
                    item.filedAt,
                )
            },
        )

        if (freshWithTicker.isNotEmpty()) {
            // Historical backfill is evidence, not a new alert. Do not notify two weeks of old filings.
            val cutoff = java.time.Instant.now().minusSeconds(6 * 3600)
            dispatchPushes(freshWithTicker.filter { (item, _) ->
                runCatching { java.time.Instant.parse(item.filedAt).isAfter(cutoff) }.getOrDefault(false)
            })
        }

        log.info("SEC EDGAR scan — new={}, ticker_matched={}", fresh.size, freshWithTicker.size)
        return fresh.size
    }

    private fun dispatchPushes(items: List<Pair<UsDisclosureItem, String>>) {
        // 모든 사용자의 US watchlist + portfolio 티커 (대문자 정규화).
        val tickersByUser = userWatchTickers.tickersByUser(market = "US")
        if (tickersByUser.isEmpty()) return
        val allUserTickers = tickersByUser.values.flatten().toSet()
        val relevant = items.flatMap { (item, ticker) ->
            allUserTickers.filter { it.replace('.', '-') == ticker.replace('.', '-') }.map { item to it }
        }.distinctBy { (item, ticker) -> "${item.accessionNo}:$ticker" }
        if (relevant.isEmpty()) return

        val devicesByUser = pushRepo.listAllDevicesGroupedByUser()
        val enabledUsers = alertPrefs.loadEnabledUsers(market = "US")

        val messages = relevant.flatMap { (item, ticker) ->
            tickersByUser.flatMap mapUser@{ (userId, userTickers) ->
                if (ticker !in userTickers || userId !in enabledUsers) return@mapUser emptyList<ExpoPushClient.Message>()
                val devices = devicesByUser[userId].orEmpty()
                devices.map { d ->
                    ExpoPushClient.Message(
                        to = d.expoToken,
                        title = "📢 ${item.companyName.take(40)} ($ticker)",
                        body = "${item.formType} 공시 — ${item.filedAt ?: "방금 접수"}",
                        data = mapOf(
                            "type" to "US_DISCLOSURE",
                            "ticker" to ticker,
                            "market" to "US",
                            "accessionNo" to item.accessionNo,
                            "url" to item.url,
                        ),
                        userId = userId,
                    )
                }
            }
        }.distinctBy { it.to to it.data["accessionNo"] }

        if (messages.isNotEmpty()) {
            expoPushClient.send(messages)
            log.info("SEC EDGAR push dispatched — messages={}", messages.size)
        }
    }
}
