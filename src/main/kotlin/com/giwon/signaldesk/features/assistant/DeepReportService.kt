package com.giwon.signaldesk.features.assistant

import com.giwon.signaldesk.common.KST
import com.giwon.signaldesk.features.backtest.application.SeasonalityRuleService
import com.giwon.signaldesk.features.market.application.MarketOverviewService
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import com.giwon.signaldesk.features.media.application.GeminiClient
import com.giwon.signaldesk.features.plan.PlanService
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * PRO 전용 — 한 종목에 대한 AI 심층 리포트.
 *
 * 기존에 이미 적재 중인 데이터만 재사용한다(신규 시세 연동 없음): 현재가, 내 보유/관심 맥락,
 * 그 종목에 저장된 시즌 규칙, 시장 합성위험. 상위 모델(gemini-2.5-pro)·긴 토큰으로
 * 평문 섹션 리포트를 생성한다. 비싼 호출이라 질문 쿼터와 별도로 하루 N회 제한.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class DeepReportService(
    private val jdbc: JdbcTemplate,
    private val geminiClient: GeminiClient,
    private val planService: PlanService,
    private val marketOverviewService: MarketOverviewService,
    private val workspaceRepository: SignalDeskWorkspaceRepository,
    private val seasonalityRuleService: SeasonalityRuleService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
    @Value("\${signal-desk.integrations.gemini.pro-model:}") private val proModel: String,
    @Value("\${signal-desk.assistant.deep-report-daily-limit:5}") private val dailyLimit: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Result(
        val report: String?,
        /** PRO 가 아니라 잠김. */
        val locked: Boolean,
        val limitExceeded: Boolean,
        val remaining: Int?,
        val dailyLimit: Int,
    )

    fun deepReport(userId: UUID, market: String, ticker: String): Result {
        val mkt = market.trim().uppercase()
        val tk = ticker.trim()
        require(tk.isNotBlank()) { "종목을 선택해 주세요." }
        require(mkt == "KR" || mkt == "US") { "지원하지 않는 시장이에요." }

        if (!planService.isPro(userId)) return Result(null, locked = true, limitExceeded = false, remaining = null, dailyLimit = dailyLimit)
        if (!geminiClient.isEnabled()) return Result(null, locked = false, limitExceeded = false, remaining = null, dailyLimit = dailyLimit)

        val today = LocalDate.now(KST)
        if (!tryConsume(userId, today, dailyLimit)) {
            return Result(null, locked = false, limitExceeded = true, remaining = 0, dailyLimit = dailyLimit)
        }

        val prompt = buildPrompt(userId, mkt, tk)
        val report = geminiClient.generateText(
            prompt,
            timeoutSeconds = 60,
            model = proModel.ifBlank { null },
            maxOutputTokens = 4096,
            disableThinking = false,
            plainTextOutput = true,
        )
        if (report == null) refund(userId, today)
        val used = usedToday(userId, today)
        log.info("deep report — user={} {}:{} ok={} used={}/{}", userId.toString().take(8), mkt, tk, report != null, used, dailyLimit)
        return Result(
            report = report,
            locked = false,
            limitExceeded = false,
            remaining = (dailyLimit - used).coerceAtLeast(0),
            dailyLimit = dailyLimit,
        )
    }

    private fun buildPrompt(userId: UUID, market: String, ticker: String): String {
        val today = LocalDate.now(KST)
        val quote = runCatching {
            if (market == "KR") krQuotes.fetchKoreanQuotes(listOf(ticker)) else usQuotes.fetchUsQuotes(listOf(ticker))
        }.getOrNull()?.get(ticker)
        val holding = runCatching { workspaceRepository.loadPortfolioPositions(userId) }.getOrDefault(emptyList())
            .find { it.market == market && it.ticker == ticker }
        val watch = runCatching { workspaceRepository.loadWatchlist(userId) }.getOrDefault(emptyList())
            .find { it.market == market && it.ticker == ticker }
        val name = holding?.name ?: watch?.name ?: ticker
        val seasonRules = runCatching {
            seasonalityRuleService.list(userId).filter { it.market == market && it.ticker == ticker }
        }.getOrDefault(emptyList())
        val summary = runCatching { marketOverviewService.getSummary(userId) }.getOrNull()

        return buildString {
            appendLine("당신은 'Signal Desk(시데)'의 PRO 전용 AI 애널리스트입니다.")
            appendLine("아래 한 종목에 대한 '심층 리포트'를 작성하세요.")
            appendLine("규칙:")
            appendLine("- 아래 [데이터] 의 내용만 근거로 합니다. 데이터에 없는 수치는 지어내지 말고 \"데이터 없음\"으로 표시합니다.")
            appendLine("- 사라/팔아라 같은 단정적 투자 지시는 하지 않습니다. 판단 재료와 확인 체크포인트만 제시합니다.")
            appendLine("- 한국어 평문. 아래 5개 섹션 제목을 그대로 쓰고, 각 섹션은 2~4문장 또는 간단한 줄바꿈 목록으로.")
            appendLine("- JSON·코드블록·마크다운 헤더(#) 금지. 섹션 제목은 그냥 텍스트 한 줄로.")
            appendLine("- 마지막 면책 문구는 앱이 따로 표시하니 덧붙이지 마세요.")
            appendLine()
            appendLine("섹션 구성:")
            appendLine("1) 종목 개요")
            appendLine("2) 내 포지션 진단")
            appendLine("3) 계절성·패턴")
            appendLine("4) 시장 환경과 리스크")
            appendLine("5) 체크포인트")
            appendLine()
            appendLine("오늘: $today (KST)")
            appendLine()
            appendLine("[데이터]")
            appendLine("## 대상 종목")
            appendLine("- $name ($market:$ticker)")
            quote?.let {
                appendLine("- 현재가 ${"%.2f".format(it.exactPrice)} (${"%+.2f".format(it.changeRate)}%)")
            } ?: appendLine("- 현재 시세: 데이터 없음")

            if (holding != null) {
                val cur = quote?.exactPrice?.takeIf { it > 0 } ?: holding.currentPrice.toDouble()
                val ret = if (holding.buyPrice > 0) (cur / holding.buyPrice - 1) * 100 else 0.0
                appendLine("## 내 보유")
                appendLine("- ${holding.quantity}주, 매수가 ${holding.buyPrice}, 현재가 ${"%.2f".format(cur)} (${"%+.1f".format(ret)}%)" +
                    (holding.targetPrice?.let { ", 목표가 $it" } ?: "") + (holding.stopLossPrice?.let { ", 손절가 $it" } ?: ""))
            } else if (watch != null) {
                appendLine("## 관심 등록")
                appendLine("- 관심종목에 담겨 있어요" + (watch.note?.takeIf { it.isNotBlank() }?.let { " (메모: $it)" } ?: ""))
            } else {
                appendLine("## 내 포지션")
                appendLine("- 보유·관심에 없는 종목이에요.")
            }

            if (seasonRules.isNotEmpty()) {
                appendLine("## 저장된 시즌 규칙(역사 패턴)")
                seasonRules.forEach { r ->
                    appendLine("- ${r.month}월 ${if (r.kind == "BUY_MONTH") "강세" else "약세"} 패턴" +
                        (r.meanPct?.let { " 평균 ${"%+.1f".format(it)}%" } ?: "") +
                        (if (r.month == today.monthValue) " (이번 달!)" else ""))
                }
            }

            summary?.let { s ->
                appendLine("## 시장 환경")
                appendLine("- 장 상태: ${s.marketStatus}")
                val risk = if (market == "KR") s.compositeRiskKr else s.compositeRiskUs
                appendLine("- 합성위험도(${market}): ${risk.score}/10 (${risk.level})")
            }

            appendLine()
            appendLine("(반드시 위 5개 섹션 제목을 그대로 쓰고 평문으로만 작성하세요.)")
        }
    }

    // ── 일일 한도 (assistant_usage 와 동일 패턴, 별도 테이블) ──────────────────
    private fun tryConsume(userId: UUID, date: LocalDate, limit: Int): Boolean =
        jdbc.update(
            """
            insert into signal_desk_deep_report_usage (user_id, usage_date, report_count) values (?::uuid, ?, 1)
            on conflict (user_id, usage_date) do update set report_count = signal_desk_deep_report_usage.report_count + 1
            where signal_desk_deep_report_usage.report_count < ?
            """.trimIndent(),
            userId.toString(), java.sql.Date.valueOf(date), limit,
        ) > 0

    private fun refund(userId: UUID, date: LocalDate) {
        runCatching {
            jdbc.update(
                "update signal_desk_deep_report_usage set report_count = greatest(report_count - 1, 0) where user_id = ?::uuid and usage_date = ?",
                userId.toString(), java.sql.Date.valueOf(date),
            )
        }
    }

    private fun usedToday(userId: UUID, date: LocalDate): Int =
        jdbc.queryForObject(
            "select coalesce(max(report_count), 0) from signal_desk_deep_report_usage where user_id = ?::uuid and usage_date = ?",
            Int::class.java, userId.toString(), java.sql.Date.valueOf(date),
        ) ?: 0
}
