package com.giwon.signaldesk.features.disclosure.application

import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import com.giwon.signaldesk.features.workspace.application.UserWatchTickerRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * SEC EDGAR 공시 5분 폴링 + 보유/관심 US 종목 매칭 시 즉시 푸시.
 *
 * KR DartDisclosureService 와 동일 패턴:
 *   1) getcurrent atom 으로 최신 8-K (최대 80건) 가져옴
 *   2) signal_desk_us_disclosure_seen 와 dedup → 신규만
 *   3) cik → ticker 해소 (SecEdgarTickerRegistry)
 *   4) 사용자 US watchlist/portfolio 와 매칭 → 해당 사용자에게 푸시
 *   5) 신규 전체를 seen 에 마킹 (매칭 안 된 것도 — 다음 스캔 재처리 방지)
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class SecEdgarDisclosureService(
    private val jdbc: JdbcTemplate,
    private val userWatchTickers: UserWatchTickerRepository,
    private val client: SecEdgarClient,
    private val tickerRegistry: SecEdgarTickerRegistry,
    private val pushRepo: PushRepository,
    private val alertPrefs: AlertPreferenceService,
    private val expoPushClient: ExpoPushClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runScan(): Int {
        val recent = client.fetchRecent(formType = "8-K", count = 80)
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
        val freshWithTicker = fresh.mapNotNull { item ->
            val ticker = tickerRegistry.resolveTicker(item.cik) ?: return@mapNotNull null
            item to ticker
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
            dispatchPushes(freshWithTicker)
        }

        log.info("SEC EDGAR scan — new={}, ticker_matched={}", fresh.size, freshWithTicker.size)
        return fresh.size
    }

    private fun dispatchPushes(items: List<Pair<UsDisclosureItem, String>>) {
        // 모든 사용자의 US watchlist + portfolio 티커 (대문자 정규화).
        val tickersByUser = userWatchTickers.tickersByUser(market = "US")
        if (tickersByUser.isEmpty()) return
        val allUserTickers = tickersByUser.values.flatten().toSet()
        val relevant = items.filter { (_, ticker) -> ticker in allUserTickers }
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
        }

        if (messages.isNotEmpty()) {
            expoPushClient.send(messages)
            log.info("SEC EDGAR push dispatched — messages={}", messages.size)
        }
    }
}
