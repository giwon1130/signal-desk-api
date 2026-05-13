package com.giwon.signaldesk.features.market.application

import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import com.giwon.signaldesk.features.workspace.application.WorkspaceAiTrackRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 매일 오전 9시 KST에 DB에 저장된 KR AI 트랙레코드의 latestPrice 와 realizedReturnRate를
 * NaverFinance 현재가 기준으로 자동 갱신한다.
 *
 * 스케줄 대상: user_id 가 있는 모든 사용자 트랙레코드 (user_id IS NULL 레거시 제외).
 * US 종목은 NaverGlobalQuoteClient 로 별도 처리 — 현재는 KR만 지원.
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AiTrackRecordSyncScheduler(
    private val workspaceStore: SignalDeskWorkspaceRepository,
    private val naverFinanceQuoteClient: NaverFinanceQuoteClient,
    private val naverGlobalQuoteClient: NaverGlobalQuoteClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    fun syncKoreanTrackRecords() {
        runCatching {
            val allRecords = workspaceStore.loadAllUserAiTrackRecords()
            if (allRecords.isEmpty()) return

            val krTickers = allRecords.filter { it.market == "KR" }.map { it.ticker }.distinct()
            val usTickers = allRecords.filter { it.market == "US" }.map { it.ticker }.distinct()

            val krQuotes = if (krTickers.isNotEmpty()) naverFinanceQuoteClient.fetchKoreanQuotes(krTickers) else emptyMap()
            val usQuotes = if (usTickers.isNotEmpty()) naverGlobalQuoteClient.fetchUsQuotes(usTickers) else emptyMap()

            var updated = 0
            allRecords.forEach { record ->
                val latestPrice = when (record.market) {
                    "KR" -> krQuotes[record.ticker]?.currentPrice
                    "US" -> usQuotes[record.ticker]?.currentPrice
                    else -> null
                } ?: return@forEach

                if (latestPrice == record.latestPrice) return@forEach
                val rate = if (record.entryPrice == 0) 0.0
                else ((latestPrice - record.entryPrice).toDouble() / record.entryPrice) * 100
                workspaceStore.updateAiTrackRecordPrice(
                    id = record.id,
                    latestPrice = latestPrice,
                    realizedReturnRate = rate,
                    success = rate >= 0,
                )
                updated++
            }
            log.info("AiTrackRecordSync completed: updated={}/{}", updated, allRecords.size)
        }.onFailure { log.error("AiTrackRecordSync failed", it) }
    }
}
