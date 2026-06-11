package com.giwon.signaldesk.features.assistant

import com.giwon.signaldesk.features.auth.application.AuthContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 시데 AI 비서 — 단발 질문/답변 (인증 필수, 레이트리밋 적용).
 *   POST /api/v1/assistant/ask  { "question": "..." }
 */
@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController(
    @Autowired(required = false) private val assistantService: AssistantService? = null,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    data class AskRequest(
        val question: String = "",
        /** 직전 대화(선택) — 클라이언트가 최근 턴을 보내 후속 질문 맥락을 잇는다. */
        val history: List<AssistantService.HistoryTurn> = emptyList(),
    )
    data class AskResponse(
        val success: Boolean,
        val answer: String?,
        val error: String? = null,
        /** 오늘 남은 질문 수 — 무제한이면 null. */
        val remaining: Int? = null,
        val dailyLimit: Int? = null,
    )

    @PostMapping("/ask")
    fun ask(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestBody req: AskRequest,
    ): AskResponse {
        val svc = assistantService ?: return AskResponse(false, null, "어시스턴트가 준비되지 않았어요.")
        val userId = authContext?.requireUserId(auth)
            ?: return AskResponse(false, null, "로그인이 필요합니다.")
        val result = svc.ask(userId, req.question, req.history)
        if (result.limitExceeded) {
            return AskResponse(false, null,
                "오늘 질문 한도(${result.dailyLimit}회)를 모두 사용했어요. 내일 다시 만나요!",
                remaining = 0, dailyLimit = result.dailyLimit)
        }
        val answer = result.answer
            ?: return AskResponse(false, null, "지금은 답변을 만들 수 없어요. 잠시 후 다시 시도해 주세요.",
                remaining = result.remaining, dailyLimit = result.dailyLimit)
        return AskResponse(true, answer, remaining = result.remaining, dailyLimit = result.dailyLimit)
    }
}
