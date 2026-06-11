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
    data class AskRequest(val question: String = "")
    data class AskResponse(val success: Boolean, val answer: String?, val error: String? = null)

    @PostMapping("/ask")
    fun ask(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestBody req: AskRequest,
    ): AskResponse {
        val svc = assistantService ?: return AskResponse(false, null, "어시스턴트가 준비되지 않았어요.")
        val userId = authContext?.requireUserId(auth)
            ?: return AskResponse(false, null, "로그인이 필요합니다.")
        val answer = svc.ask(userId, req.question)
            ?: return AskResponse(false, null, "지금은 답변을 만들 수 없어요. 잠시 후 다시 시도해 주세요.")
        return AskResponse(true, answer)
    }
}
