package com.giwon.signaldesk.features.assistant

import com.giwon.signaldesk.common.KST
import com.giwon.signaldesk.features.backtest.application.SeasonalityRuleService
import com.giwon.signaldesk.features.market.application.MarketOverviewService
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import com.giwon.signaldesk.features.media.application.GeminiClient
import com.giwon.signaldesk.features.media.application.MediaSummaryRepository
import com.giwon.signaldesk.features.push.application.PushRepository
import com.giwon.signaldesk.features.workspace.application.SignalDeskWorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * 시데 AI 비서 (Phase 1) — 사용자의 데이터(보유/관심/알림/브리프)와 오늘 시장 상태를
 * "컨텍스트 팩"으로 조립해 Gemini 에 단발 질문/답변.
 *
 * 가드레일: 제공 데이터만 근거로 답하고, 없으면 모른다고 말하게 한다.
 * 매수/매도 지시 금지 — 참고 정보와 체크포인트 형태로만.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AssistantService(
    private val geminiClient: GeminiClient,
    private val marketOverviewService: MarketOverviewService,
    private val workspaceRepository: SignalDeskWorkspaceRepository,
    private val pushRepository: PushRepository,
    private val mediaSummaryRepository: MediaSummaryRepository,
    private val seasonalityRuleService: SeasonalityRuleService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ask(userId: UUID, question: String): String? {
        val q = question.trim()
        require(q.isNotBlank()) { "질문을 입력해 주세요." }
        require(q.length <= MAX_QUESTION_LENGTH) { "질문은 ${MAX_QUESTION_LENGTH}자 이내로 입력해 주세요." }
        if (!geminiClient.isEnabled()) return null

        val prompt = buildPrompt(userId, q)
        val answer = geminiClient.generateText(prompt, timeoutSeconds = 30)
        log.info("assistant ask — user={} qLen={} answered={}", userId.toString().take(8), q.length, answer != null)
        return answer
    }

    private fun buildPrompt(userId: UUID, question: String): String {
        val today = LocalDate.now(KST)
        val summary = runCatching { marketOverviewService.getSummary(userId) }.getOrNull()
        val portfolio = runCatching { workspaceRepository.loadPortfolioPositions(userId) }.getOrDefault(emptyList())
        val watchlist = runCatching { workspaceRepository.loadWatchlist(userId) }.getOrDefault(emptyList()).take(15)
        val alerts = runCatching { pushRepository.listAlertHistory(userId, 5) }.getOrDefault(emptyList())
        val brief = runCatching { mediaSummaryRepository.findRecent(1).firstOrNull() }.getOrNull()
        val seasonRules = runCatching {
            seasonalityRuleService.list(userId).filter { it.month == today.monthValue }
        }.getOrDefault(emptyList())

        // 보유 종목 라이브 시세 — stored current_price 는 사용자가 안 들어오면 안 갱신된다.
        val krLive = portfolio.filter { it.market == "KR" }.map { it.ticker }.distinct()
            .takeIf { it.isNotEmpty() }?.let { runCatching { krQuotes.fetchKoreanQuotes(it) }.getOrNull() }.orEmpty()
        val usLive = portfolio.filter { it.market == "US" }.map { it.ticker }.distinct()
            .takeIf { it.isNotEmpty() }?.let { runCatching { usQuotes.fetchUsQuotes(it) }.getOrNull() }.orEmpty()

        return buildString {
            appendLine("당신은 'Signal Desk(시데)'라는 개인 투자 대시보드의 AI 비서입니다.")
            appendLine("규칙:")
            appendLine("- 아래 [데이터] 섹션의 내용만 근거로 답합니다. 데이터에 없는 것은 \"제 데이터에는 없어서 알 수 없어요\"라고 말합니다.")
            appendLine("- 종목을 사라/팔아라 같은 단정적 투자 지시는 하지 않습니다. 데이터 기반 참고 정보와 확인할 체크포인트만 제시합니다.")
            appendLine("- 한국어, 친근한 존댓말로 간결하게(3~6문장). 숫자는 데이터 그대로 인용합니다.")
            appendLine("- 마지막에 면책 문구를 덧붙이지 마세요 (앱이 별도 표시).")
            appendLine()
            appendLine("오늘: $today (KST)")
            appendLine()
            appendLine("[데이터]")

            summary?.let { s ->
                appendLine("## 시장 상태")
                appendLine("- 장 상태: ${s.marketStatus}")
                appendLine("- 합성위험도: KR ${s.compositeRiskKr.score}/10(${s.compositeRiskKr.level}) · US ${s.compositeRiskUs.score}/10(${s.compositeRiskUs.level})")
                s.marketSummary.forEach { m -> appendLine("- ${m.label}: ${m.score.toInt()} (${m.state})") }
            }

            if (portfolio.isNotEmpty()) {
                appendLine("## 보유 종목")
                portfolio.forEach { p ->
                    val live = (if (p.market == "KR") krLive else usLive)[p.ticker]?.exactPrice?.takeIf { it > 0 }
                    val current = live ?: p.currentPrice.toDouble()
                    val ret = if (p.buyPrice > 0) (current / p.buyPrice - 1) * 100 else 0.0
                    appendLine("- ${p.name}(${p.market}:${p.ticker}) ${p.quantity}주, 매수가 ${p.buyPrice}, 현재가 ${"%.2f".format(current)} (${"%+.1f".format(ret)}%)" +
                        (p.targetPrice?.let { ", 목표가 $it" } ?: "") + (p.stopLossPrice?.let { ", 손절가 $it" } ?: ""))
                }
            }

            if (watchlist.isNotEmpty()) {
                appendLine("## 관심 종목")
                watchlist.forEach { w -> appendLine("- ${w.name}(${w.market}:${w.ticker}) ${w.price} (${"%+.1f".format(w.changeRate)}%)") }
            }

            if (alerts.isNotEmpty()) {
                appendLine("## 최근 받은 알림")
                alerts.forEach { a ->
                    appendLine("- ${a.alertDate} ${a.name}(${a.ticker}) ${a.direction} ${"%+.1f".format(a.changeRate)}%" +
                        (a.reason?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
                }
            }

            brief?.let {
                appendLine("## 최신 브리프 (${it.videoTitle})")
                appendLine(it.summary.take(800))
            }

            if (seasonRules.isNotEmpty()) {
                appendLine("## 이번 달 시즌 규칙 (사용자가 저장한 역사 패턴)")
                seasonRules.forEach { r ->
                    appendLine("- ${r.name}(${r.market}:${r.ticker}) ${r.month}월 ${if (r.kind == "BUY_MONTH") "강세" else "약세"}" +
                        (r.meanPct?.let { " 평균 ${"%+.1f".format(it)}%" } ?: ""))
                }
            }

            appendLine()
            appendLine("[질문]")
            appendLine(question)
        }
    }

    companion object {
        const val MAX_QUESTION_LENGTH = 500
    }
}
