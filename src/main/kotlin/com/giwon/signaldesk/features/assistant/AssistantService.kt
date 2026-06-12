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
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    private val geminiClient: GeminiClient,
    private val marketOverviewService: MarketOverviewService,
    private val workspaceRepository: SignalDeskWorkspaceRepository,
    private val pushRepository: PushRepository,
    private val mediaSummaryRepository: MediaSummaryRepository,
    private val seasonalityRuleService: SeasonalityRuleService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
    private val planService: com.giwon.signaldesk.features.plan.PlanService,
    @org.springframework.beans.factory.annotation.Value("\${signal-desk.assistant.free-daily-limit:10}") private val freeDailyLimit: Int,
    @org.springframework.beans.factory.annotation.Value("\${signal-desk.assistant.pro-daily-limit:100}") private val proDailyLimit: Int,
    @org.springframework.beans.factory.annotation.Value("\${signal-desk.assistant.admin-emails:}") adminEmailsRaw: String,
    @org.springframework.beans.factory.annotation.Value("\${signal-desk.integrations.gemini.pro-model:}") private val proModel: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val adminEmails = adminEmailsRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    data class AskResult(
        val answer: String?,
        val limitExceeded: Boolean,
        /** 오늘 남은 질문 수. 무제한(운영자)이면 null. */
        val remaining: Int?,
        val dailyLimit: Int?,
    )

    /** 직전 대화 한 턴 — role: "user" | "assistant". */
    data class HistoryTurn(val role: String = "user", val text: String = "")

    fun ask(userId: UUID, question: String, history: List<HistoryTurn> = emptyList()): AskResult {
        val q = question.trim()
        require(q.isNotBlank()) { "질문을 입력해 주세요." }
        require(q.length <= MAX_QUESTION_LENGTH) { "질문은 ${MAX_QUESTION_LENGTH}자 이내로 입력해 주세요." }
        if (!geminiClient.isEnabled()) return AskResult(null, limitExceeded = false, remaining = null, dailyLimit = null)

        val today = LocalDate.now(KST)
        val limit = dailyLimitFor(userId)

        // 한도 차감 (원자적) — 무제한(null)이면 카운트만 기록.
        if (limit != null && !tryConsume(userId, today, limit)) {
            return AskResult(null, limitExceeded = true, remaining = 0, dailyLimit = limit)
        }
        if (limit == null) recordUnlimitedUsage(userId, today)

        val prompt = buildPrompt(userId, q, history)
        // PRO 는 상위 모델 + 긴 답변(thinking 허용). FREE 는 기존 기본 모델·2048토큰.
        val pro = runCatching { planService.isPro(userId) }.getOrDefault(false)
        val answer = geminiClient.generateText(
            prompt,
            timeoutSeconds = if (pro) 45 else 30,
            model = if (pro) proModel.ifBlank { null } else null,
            maxOutputTokens = if (pro) 4096 else 2048,
            disableThinking = !pro,
        )?.let(::unwrapPlainText)
        if (answer == null && limit != null) refund(userId, today) // Gemini 실패는 한도에서 환불
        val used = usedToday(userId, today)
        log.info("assistant ask — user={} qLen={} answered={} used={}/{}", userId.toString().take(8), q.length, answer != null, used, limit ?: -1)
        return AskResult(
            answer = answer,
            limitExceeded = false,
            remaining = limit?.let { (it - used).coerceAtLeast(0) },
            dailyLimit = limit,
        )
    }

    // ── 한도 ────────────────────────────────────────────────────────────────
    /** null = 무제한(운영자). PRO 는 pro-daily-limit, 그 외 free-daily-limit. */
    private fun dailyLimitFor(userId: UUID): Int? {
        val row = jdbc.query(
            "select email, plan from signal_desk_users where id = ?::uuid",
            { rs, _ -> rs.getString("email").lowercase() to (rs.getString("plan") ?: "FREE") },
            userId.toString(),
        ).firstOrNull() ?: return freeDailyLimit
        val (email, plan) = row
        if (email in adminEmails) return null
        return if (plan.equals("PRO", ignoreCase = true)) proDailyLimit else freeDailyLimit
    }

    /** count < limit 일 때만 +1 — 동시 요청에도 한도를 안 넘게 조건부 upsert. */
    private fun tryConsume(userId: UUID, date: LocalDate, limit: Int): Boolean =
        jdbc.update(
            """
            insert into signal_desk_assistant_usage (user_id, usage_date, question_count) values (?::uuid, ?, 1)
            on conflict (user_id, usage_date) do update set question_count = signal_desk_assistant_usage.question_count + 1
            where signal_desk_assistant_usage.question_count < ?
            """.trimIndent(),
            userId.toString(), java.sql.Date.valueOf(date), limit,
        ) > 0

    private fun recordUnlimitedUsage(userId: UUID, date: LocalDate) {
        runCatching {
            jdbc.update(
                """
                insert into signal_desk_assistant_usage (user_id, usage_date, question_count) values (?::uuid, ?, 1)
                on conflict (user_id, usage_date) do update set question_count = signal_desk_assistant_usage.question_count + 1
                """.trimIndent(),
                userId.toString(), java.sql.Date.valueOf(date),
            )
        }
    }

    private fun refund(userId: UUID, date: LocalDate) {
        runCatching {
            jdbc.update(
                "update signal_desk_assistant_usage set question_count = greatest(question_count - 1, 0) where user_id = ?::uuid and usage_date = ?",
                userId.toString(), java.sql.Date.valueOf(date),
            )
        }
    }

    private fun usedToday(userId: UUID, date: LocalDate): Int =
        jdbc.queryForObject(
            "select coalesce(max(question_count), 0) from signal_desk_assistant_usage where user_id = ?::uuid and usage_date = ?",
            Int::class.java, userId.toString(), java.sql.Date.valueOf(date),
        ) ?: 0

    /**
     * 모델이 지시를 무시하고 JSON/코드블록으로 감싸는 경우 방어.
     * 단일 문자열 필드면 내용물만, 다중 필드/배열이면 문자열 leaf 들을 문장으로 결합 —
     * "포트폴리오 점검" 류 질문에서 {"보유":"...","조언":"..."} 형태가 관측됨.
     */
    private fun unwrapPlainText(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            runCatching {
                val node = objectMapper.readTree(t)
                val leaves = ArrayList<String>()
                collectTextLeaves(node, leaves)
                if (leaves.isNotEmpty()) return leaves.joinToString(" ").trim()
            }
        }
        // 전체가 따옴표 한 쌍으로 감싸진 경우(JSON 문자열 흉내)도 벗긴다.
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    private fun collectTextLeaves(node: com.fasterxml.jackson.databind.JsonNode, out: MutableList<String>) {
        when {
            node.isTextual -> node.asText().trim().takeIf { it.isNotBlank() }?.let { out.add(it) }
            node.isArray || node.isObject -> node.forEach { collectTextLeaves(it, out) }
        }
    }

    private fun buildPrompt(userId: UUID, question: String, history: List<HistoryTurn> = emptyList()): String {
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
            appendLine("- 출력은 평문 문장만. JSON·마크다운·코드블록·헤더를 쓰지 마세요.")
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

            // 직전 대화 — 후속 질문("그럼 언제가 좋아?")의 맥락. 토큰 상한을 위해 최근 턴만, 길이 제한.
            val recent = history.takeLast(MAX_HISTORY_TURNS).filter { it.text.isNotBlank() }
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("[직전 대화]")
                recent.forEach { t ->
                    val who = if (t.role == "assistant") "비서" else "사용자"
                    appendLine("$who: ${t.text.take(MAX_HISTORY_TURN_LENGTH)}")
                }
            }

            appendLine()
            appendLine("[질문]")
            appendLine(question)
            // 모델은 프롬프트 끝의 지시를 가장 잘 따른다 — 평문 강제를 한 번 더.
            appendLine()
            appendLine("(반드시 평문 문장으로만 답하세요. JSON, 마크다운, 목록 기호, 코드블록 금지.)")
        }
    }

    companion object {
        const val MAX_QUESTION_LENGTH = 500
        const val MAX_HISTORY_TURNS = 6
        const val MAX_HISTORY_TURN_LENGTH = 600
    }
}
