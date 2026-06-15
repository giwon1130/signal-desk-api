package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverStockChartClient
import com.giwon.signaldesk.features.reading.domain.AiLeaders
import com.giwon.signaldesk.features.reading.domain.PostVisibility
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 📈 AI 리포트 콜 — 한경 컨센서스(공개 증권사 리포트)의 목표주가를 AI 리더 콜로 발행.
 *
 * Buy + 목표가 있는 신규 리포트 → 현재가(진입가) 잠금 → 목표수익률 계산 → AI 손절가(진입가 -N%) →
 * '📈 AI 리포트 콜' 리더 글+콜로 발행(구독자 피드, 적중률 자동 추적). report_idx 로 중복 방지.
 * 콜이므로 PRO 구독자만 본다(AI 리더 구독 PRO 게이트).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class ReportCallService(
    private val consensus: HankyungConsensusClient,
    private val reading: ReadingService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val chart: NaverStockChartClient,
    private val jdbc: JdbcTemplate,
    @Value("\${signal-desk.report-call.daily-limit:5}") private val dailyLimit: Int,
    @Value("\${signal-desk.report-call.stop-loss-pct:8}") private val stopLossPct: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 신규 Buy 리포트를 콜로 발행. 발행 건수 반환. */
    fun run(force: Boolean = false): Int {
        val buys = consensus.fetchRecent().filter { isBuy(it.opinion) && it.targetPrice > 0 }
        if (buys.isEmpty()) { log.info("ReportCall: no buy reports"); return 0 }

        // 후보 시세 일괄 조회.
        val prices = runCatching { krQuotes.fetchKoreanQuotes(buys.map { it.ticker }.distinct()) }.getOrNull().orEmpty()

        var published = 0
        for (r in buys) {
            if (published >= dailyLimit) break
            if (!force && isSeen(r.reportIdx)) continue
            val entry = prices[r.ticker]?.exactPrice?.takeIf { it > 0 } ?: continue
            if (r.targetPrice <= entry) continue  // 상승 여력 없는 콜은 스킵

            val targetReturnPct = ((r.targetPrice - entry) / entry) * 100
            val (stop, stopBasis) = suggestStop(r.ticker, entry)
            val stopPct = (1 - stop.toDouble() / entry) * 100
            val title = "${r.name} 목표가 ${"%,d".format(r.targetPrice)}원 · ${r.firm}"
            val body = buildString {
                append("${r.firm} 리포트 — 목표가 ${"%,d".format(r.targetPrice)}원")
                append(" (진입가 ${"%,d".format(Math.round(entry))}원 대비 ${"%+.1f".format(targetReturnPct)}%)\n")
                append("🤖 AI 제안 손절가 ${"%,d".format(stop)}원 (-${"%.1f".format(stopPct)}%, $stopBasis)\n")
                append("출처: ${r.firm} 공개 리포트 · 참고용이며 투자자문이 아닙니다.")
            }

            val ok = runCatching {
                reading.publishPost(
                    AiLeaders.REPORT, title = title, body = body,
                    visibility = PostVisibility.FOLLOWERS,
                    confirmedCalls = listOf(
                        ReadingService.CallInput(
                            market = "KR", ticker = r.ticker, name = r.name,
                            targetReturnPct = BigDecimal(targetReturnPct).setScale(2, RoundingMode.HALF_UP),
                        ),
                    ),
                )
            }.onFailure { log.warn("ReportCall publish failed for {} ({})", r.name, r.ticker, it) }.isSuccess

            if (ok) { markSeen(r.reportIdx); published++; log.info("ReportCall published {} {} target={}", r.name, r.ticker, r.targetPrice) }
        }
        return published
    }

    /**
     * AI 제안 손절가 — 진짜 ATR(14, 고가/저가 기반) 손절과 20일 전저점 중 더 타이트한 쪽, 진입가 -3%~-15% 클램프.
     * 네이버 일봉 OHLC 사용. TR=max(고-저, |고-전일종가|, |저-전일종가|), ATR=최근14 TR 평균, ATR손절=진입가-2*ATR.
     * 데이터 부족 시 기본 -N% 폴백.
     */
    private fun suggestStop(code: String, entry: Double): Pair<Int, String> {
        val fallback = Math.round(entry * (100 - stopLossPct) / 100).toInt() to "진입가 -${stopLossPct}%"
        val bars = runCatching { chart.fetchDailyOhlc(code, 30) }.getOrNull().orEmpty()
        if (bars.size < 15) return fallback

        // ATR(14) — TR 의 최근 14개 평균.
        val trs = bars.zipWithNext { prev, cur ->
            maxOf(cur.high - cur.low, kotlin.math.abs(cur.high - prev.close), kotlin.math.abs(cur.low - prev.close))
        }
        val atr = trs.takeLast(14).average()
        val atrStop = entry - 2 * atr                          // 2 ATR 하락
        val recentLow = bars.takeLast(20).minOf { it.low }     // 실제 저가 전저점
        val raw = maxOf(recentLow, atrStop)                    // 더 타이트(진입가에 가까운) 쪽 = 리스크 제한
        val stop = raw.coerceIn(entry * 0.85, entry * 0.97)    // -3% ~ -15%
        return Math.round(stop).toInt() to "ATR(14)·전저점"
    }

    private fun isBuy(opinion: String): Boolean {
        val o = opinion.uppercase()
        return o.contains("BUY") || opinion.contains("매수") || o.contains("STRONGBUY") || o.contains("OUTPERFORM")
    }

    private fun isSeen(reportIdx: String): Boolean =
        (jdbc.queryForObject("select count(*) from signal_desk_report_call_seen where report_key = ?", Int::class.java, reportIdx) ?: 0) > 0

    private fun markSeen(reportIdx: String) {
        runCatching {
            jdbc.update("insert into signal_desk_report_call_seen (report_key) values (?) on conflict (report_key) do nothing", reportIdx)
        }
    }
}
