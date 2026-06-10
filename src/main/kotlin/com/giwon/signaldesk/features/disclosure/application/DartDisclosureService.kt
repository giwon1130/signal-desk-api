package com.giwon.signaldesk.features.disclosure.application

import com.giwon.signaldesk.features.push.application.AlertPreferenceService
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * OpenDART 공시 5분 폴링 + 보유/관심 종목 매칭 시 즉시 푸시.
 *
 * 흐름:
 *   1) 오늘 날짜로 list.json page 1 호출 (최신 100건, sort desc)
 *      - 100건 안에 신규 공시 다 들어옴 (5분 폴링 + 시장 전체 분당 공시량 고려 시 안전 마진)
 *   2) seen 테이블과 dedup → 신규만
 *   3) 모든 사용자의 KR watchlist + portfolio stock_code 집계
 *   4) stock_code 매칭되는 공시 → 해당 사용자에게만 푸시
 *   5) 신규 전체를 seen 에 마킹 (매칭 안 된 것도 — 다음 스캔에서 재처리 방지)
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DartDisclosureService(
    private val jdbc: JdbcTemplate,
    private val client: DartDisclosureClient,
    private val seenRepo: DisclosureSeenRepository,
    private val pushRepo: PushRepository,
    private val alertPrefs: AlertPreferenceService,
    private val expoPushClient: ExpoPushClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ymdFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 단일 스캔. 처리된 신규 공시 수 반환. */
    fun runScan(): Int {
        val today = LocalDate.now(clock)
        val resp = client.fetchRecent(today.format(ymdFmt), pageNo = 1, pageCount = 100)
            ?: return 0
        if (resp.status != "000") {
            // "013" = 조회된 데이터 없음 (장 시작 전 정상 케이스). 그 외는 경고.
            if (resp.status != "013") log.warn("dart list status={} message={}", resp.status, resp.message)
            return 0
        }
        if (resp.list.isEmpty()) return 0

        val byRceptNo = resp.list.associateBy { it.rceptNo }
        val newRceptNos = seenRepo.filterUnseen(byRceptNo.keys)
        if (newRceptNos.isEmpty()) return 0
        val newItems = newRceptNos.mapNotNull { byRceptNo[it] }
        log.info("dart scan — new disclosures count={}", newItems.size)

        // 상장사 + 사용자 보유/관심 종목 매칭만 푸시
        val listed = newItems.filter { it.stockCode.length == 6 && it.stockCode.all(Char::isDigit) }
        if (listed.isNotEmpty()) {
            dispatchPushes(listed)
        }

        seenRepo.markSeen(newItems.map { it.toDisclosure() })
        return newItems.size
    }

    private fun dispatchPushes(items: List<DartListItem>) {
        val tickersByUser = loadUserKrTickers()  // Map<UUID, Set<String>>
        if (tickersByUser.isEmpty()) return
        val allTickers = tickersByUser.values.flatten().toSet()
        // 보유/관심 종목 + HIGH(주가에 직접 작용하는 핵심 사안)만 푸시. MEDIUM·LOW 는 앱에서만 확인.
        val relevant = items.filter {
            it.stockCode in allTickers &&
                DisclosureClassifier.classify(it.reportNm) == DisclosureImportance.HIGH
        }
        if (relevant.isEmpty()) return

        val devicesByUser = pushRepo.listAllDevicesGroupedByUser()
        val enabledUsers = alertPrefs.loadEnabledUsers(market = "KR")

        // 공시 × 사용자 × 기기 메시지를 모두 모아 한 번에 발송 — Expo Push API 배치 전송.
        val messages = relevant.flatMap { item ->
            tickersByUser.flatMap userMap@{ (userId, tickers) ->
                if (item.stockCode !in tickers || userId !in enabledUsers) return@userMap emptyList()
                val devices = devicesByUser[userId] ?: return@userMap emptyList()
                devices.map { d ->
                    ExpoPushClient.Message(
                        to = d.expoToken,
                        title = "📢 ${item.corpName}",
                        body = item.reportNm.take(80),
                        data = mapOf(
                            "type" to "DISCLOSURE",
                            "rceptNo" to item.rceptNo,
                            "stockCode" to item.stockCode,
                            "url" to "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${item.rceptNo}",
                        ),
                    )
                }
            }
        }
        expoPushClient.send(messages)
        log.info("dart disclosure push dispatched. items={}, messages={}", relevant.size, messages.size)
    }

    /** 모든 사용자의 KR watchlist + portfolio stock_code (6자리 숫자만). user_id 가 null 인 레거시 row 는 제외. */
    private fun loadUserKrTickers(): Map<UUID, Set<String>> {
        val watch = jdbc.query(
            "select user_id, ticker from signal_desk_watchlist where market = 'KR' and user_id is not null",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
        )
        val portfolio = jdbc.query(
            "select user_id, ticker from signal_desk_portfolio_positions where market = 'KR' and user_id is not null",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
        )
        return (watch + portfolio)
            .filter { it.second.length == 6 && it.second.all(Char::isDigit) }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    /** 단일 사용자의 보유/관심 KR 종목 최근 공시. */
    fun listRecentForUser(userId: UUID, limit: Int = 30): List<Disclosure> {
        val tickers = jdbc.queryForList(
            """
            select ticker from signal_desk_watchlist where user_id = ?::uuid and market = 'KR'
            union
            select ticker from signal_desk_portfolio_positions where user_id = ?::uuid and market = 'KR'
            """.trimIndent(),
            String::class.java,
            userId.toString(), userId.toString(),
        ).toSet()
        return seenRepo.findRecentByStockCodes(tickers, limit)
    }
}
