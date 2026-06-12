package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.ai.application.AiPick
import com.giwon.signaldesk.features.ai.application.PickCandidate
import com.giwon.signaldesk.features.events.application.MarketEvent
import com.giwon.signaldesk.features.market.application.InvestorFlowSnapshot
import com.giwon.signaldesk.features.market.application.MacroSnapshot
import com.giwon.signaldesk.features.market.application.GlobalIndex
import com.giwon.signaldesk.features.market.application.MarketNews
import com.giwon.signaldesk.features.market.application.MarketSection
import com.giwon.signaldesk.features.market.application.TopMover
import com.giwon.signaldesk.features.market.application.UsIndicesSnapshot
import com.giwon.signaldesk.features.market.application.VixSnapshot
import com.giwon.signaldesk.features.market.application.YahooQuote
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Google Gemini API (generativeContent) 클라이언트.
 *
 * 모델: 정식 GA 모델 사용(gemini-2.0-flash). 실험(-exp)/퇴역(1.5) 모델은
 * 분당 한도·과부하·deprecate 에 취약해 제외. 일 호출은 ~수십 회로 무료 한도 충분,
 * 병목은 분당 한도(동시 잡 충돌)라 안정 모델 + fallback 으로 흡수한다.
 *
 * 응답 형식 강제: responseMimeType=application/json 으로 JSON 만 받게 한다.
 *
 * 책임 분리:
 *  - 본 클래스: HTTP 호출, 재시도, isEnabled 게이트, public summarizeXxx() API
 *  - [GeminiPrompts]: 5종 시나리오 프롬프트 문자열 빌더
 *  - [GeminiResponseParsing]: 3종 응답 타입 JSON 파서
 */
@Component
class GeminiClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.gemini.api-key}") private val apiKey: String,
    @Value("\${signal-desk.integrations.gemini.fallback-keys:}") private val fallbackKeysRaw: String,
    @Value("\${signal-desk.integrations.gemini.base-url}") private val baseUrl: String,
    @Value("\${signal-desk.integrations.gemini.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    // 키 체인: [primary, ...fallbacks]. primary 가 일일 쿼터(429-quota)로 소진되면 다음 키로 폴백.
    // primary·fallback 어느 쪽이 비어도(로테이션 중) blank 제거 후 살아있는 키만 순서대로 사용.
    private val apiKeys: List<String> =
        (listOf(apiKey) + fallbackKeysRaw.split(","))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    // Fallback chain: 주 모델 503 과부하 시 다음 모델 시도. 현행 정식 GA 모델로 구성.
    // primary 가 fallback 과 중복되면 distinct 로 제거.
    // gemini-2.0-flash 는 2026-06-01 deprecated 라 제외 — 죽은 슬롯이 폴백을 낭비하지 않게 한다.
    private val modelChain: List<String> =
        listOf(model, "gemini-2.5-flash", "gemini-2.5-flash-lite").distinct()

    // 시스템 헬스 — 마지막 모든 키·모델 실패 시각. 사용자에게 "일시 장애" 안내용.
    // 성공 호출이 한 번이라도 들어오면 자가 회복 — null 로 reset.
    @Volatile private var lastFailureAt: Instant? = null

    fun isEnabled(): Boolean = apiKeys.isNotEmpty()

    /** API 키 마스킹 — 로깅용. 앞4/뒤4만 노출, 그 외는 전부 가린다. */
    private fun mask(key: String): String =
        if (key.length <= 8) "****" else "${key.take(4)}…${key.takeLast(4)}"

    /** 최근 5분 내 모든 모델 fallback 실패 없었으면 healthy. */
    fun isHealthy(): Boolean {
        val t = lastFailureAt ?: return true
        return Duration.between(t, Instant.now()).toMinutes() >= 5
    }

    fun lastFailureAt(): Instant? = lastFailureAt

    // ─── public API: 시나리오별 요약 ──────────────────────────────────────────

    fun summarizeMarketInsight(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        headlines: List<MarketNews>,
        upcomingEvents: List<MarketEvent> = emptyList(),
    ): MarketInsightAnalysis? = callInsight(GeminiPrompts.marketInsight(vix, indices, headlines, upcomingEvents))

    /**
     * 모닝 브리프 — 야간 미국장 결과 + KR 뉴스 + 보유/관심 종목 공시를 합쳐
     * 장 시작 전(08:30 KST) 한국 개인 투자자가 오늘 대응을 준비할 수 있게 종합한다.
     */
    fun summarizeMorningBrief(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        macro: MacroSnapshot?,
        headlines: List<MarketNews>,
        disclosureTitles: List<String>,
        investorFlow: InvestorFlowSnapshot? = null,
        upcomingEvents: List<MarketEvent> = emptyList(),
        krMarket: MarketSection? = null,
        krGainers: List<TopMover> = emptyList(),
        krLosers: List<TopMover> = emptyList(),
        earningsSymbols: List<String> = emptyList(),
        global: List<GlobalIndex> = emptyList(),
    ): MarketInsightAnalysis? = callInsight(
        GeminiPrompts.morningBrief(
            vix, indices, macro, headlines, disclosureTitles, investorFlow, upcomingEvents,
            krMarket, krGainers, krLosers, earningsSymbols, global,
        ),
    )

    /** KR 장중/마감 브리프 — slot="MIDDAY"|"CLOSE". 모닝 브리프와 같은 입력을 KR 관점으로. */
    fun summarizeIntradayBrief(
        slot: String,
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        macro: MacroSnapshot?,
        headlines: List<MarketNews>,
        investorFlow: InvestorFlowSnapshot? = null,
        upcomingEvents: List<MarketEvent> = emptyList(),
        krMarket: MarketSection? = null,
        krGainers: List<TopMover> = emptyList(),
        krLosers: List<TopMover> = emptyList(),
        global: List<GlobalIndex> = emptyList(),
    ): MarketInsightAnalysis? = callInsight(
        GeminiPrompts.intradayBrief(
            slot, vix, indices, macro, headlines, investorFlow, upcomingEvents,
            krMarket, krGainers, krLosers, global,
        ),
    )

    /**
     * 미장 이브닝 브리프 — NY 장 마감 직후(06:30 KST). 야간 미국장 결과 + top movers + 실적 + 헤드라인을
     * 종합해 한국 투자자에게 "어제 미국장 어땠고 오늘 한국장에 어떤 영향 있을지" 한 줄 요약.
     */
    fun summarizeEveningBrief(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        topGainers: List<YahooQuote>,
        topLosers: List<YahooQuote>,
        earningsSymbols: List<String>,
        headlines: List<MarketNews>,
    ): MarketInsightAnalysis? = callInsight(
        GeminiPrompts.eveningBrief(vix, indices, topGainers, topLosers, earningsSymbols, headlines),
    )

    /**
     * 마감시황 뉴스 헤드라인 묶음을 종합 요약.
     * @param marketLabel "KR" 또는 "US"
     * @param dateLabel "2026-05-15" 같은 날짜
     * @param headlines (source, title, url) 튜플 리스트
     */
    fun summarizeNewsDigest(
        marketLabel: String,
        dateLabel: String,
        headlines: List<Triple<String, String, String>>,
    ): MediaSummaryAnalysis? = callDigest(GeminiPrompts.newsDigest(marketLabel, dateLabel, headlines))

    /**
     * 오늘의 AI 픽 — 후보 종목 universe 안에서 단타 관점 주목 종목 3~5개를 골라 이유와 함께 반환.
     * candidates 밖 종목은 환각이므로 호출부(AiPickService)에서 추가 필터한다.
     */
    fun summarizeAiPicks(
        candidates: List<PickCandidate>,
        headlines: List<MarketNews>,
    ): AiPicksAnalysis? {
        val body = call(GeminiPrompts.aiPicks(candidates, headlines), timeoutSeconds = 30, label = "ai picks") ?: return null
        return runCatching { GeminiResponseParsing.aiPicks(body, objectMapper) }.getOrElse {
            log.warn("Gemini AI picks parse failed", it)
            null
        }
    }

    /**
     * 급등/급락 종목별 사유 — 종목+매칭 헤드라인을 한 번에 배치로 보내 종목별 한 줄 사유를 받는다.
     * 실패/비활성 시 빈 리스트 (호출부에서 캐시 유지).
     */
    fun summarizeMoverReasons(
        dateLabel: String,
        movers: List<MoverReasonInput>,
    ): List<MoverReasonAnalysis> {
        if (movers.isEmpty()) return emptyList()
        val body = call(GeminiPrompts.moverReasons(dateLabel, movers), timeoutSeconds = 30, label = "mover reasons")
            ?: return emptyList()
        return runCatching { GeminiResponseParsing.moverReasons(body, objectMapper) }.getOrElse {
            log.warn("Gemini mover reasons parse failed", it)
            emptyList()
        }
    }

    /**
     * 어시스턴트 등 자유 질문용 — 프롬프트 → 평문 응답 텍스트 (실패 시 null).
     *
     * PRO 경로는 [model] 로 상위 모델(gemini-2.5-pro)을 체인 맨 앞에 두고, [maxOutputTokens] 를
     * 키워 긴 답변을 받는다. 상위 모델은 thinking 비활성화를 거부하므로 [disableThinking]=false 로
     * 두되 plainText 파서가 thought 파트를 걸러낸다. [plainTextOutput]=true 면 JSON 강제 대신
     * 평문 mime — 줄바꿈 있는 섹션 리포트용.
     */
    fun generateText(
        prompt: String,
        timeoutSeconds: Long = 30,
        model: String? = null,
        maxOutputTokens: Int = 2048,
        disableThinking: Boolean = true,
        plainTextOutput: Boolean = false,
    ): String? {
        if (!isEnabled()) {
            log.warn("GeminiClient disabled (assistant) — GEMINI_API_KEY 미설정")
            return null
        }
        val chain = if (model.isNullOrBlank()) modelChain else (listOf(model) + modelChain).distinct()
        val content = buildPayload(prompt, maxOutputTokens, jsonOutput = !plainTextOutput, disableThinking = disableThinking)
        val body = postWithRetry(content, timeoutSeconds, chain) ?: return null
        return runCatching { GeminiResponseParsing.plainText(body, objectMapper) }.getOrElse {
            log.warn("Gemini plain text parse failed", it)
            null
        }
    }

    // ─── private: 공통 호출 + 파싱 ────────────────────────────────────────────

    private fun callInsight(prompt: String): MarketInsightAnalysis? {
        val body = call(prompt, timeoutSeconds = 30, label = "insight") ?: return null
        return runCatching { GeminiResponseParsing.insight(body, objectMapper) }.getOrElse {
            log.warn("Gemini market insight parse failed", it)
            null
        }
    }

    private fun callDigest(prompt: String): MediaSummaryAnalysis? {
        val body = call(prompt, timeoutSeconds = 45, label = "news digest") ?: return null
        return runCatching { GeminiResponseParsing.newsDigest(body, objectMapper) }.getOrElse {
            log.warn("Gemini news digest parse failed", it)
            null
        }
    }

    /** isEnabled 가드 + postWithRetry. 응답 body 반환 (성공 시) 또는 null. */
    private fun call(prompt: String, timeoutSeconds: Long, label: String): String? {
        if (!isEnabled()) {
            log.warn("GeminiClient disabled ($label) — GEMINI_API_KEY 미설정")
            return null
        }
        return postWithRetry(buildPayload(prompt), timeoutSeconds, modelChain)
    }

    /**
     * Gemini generateContent 호출 + 모델 fallback + 키 fallback.
     *
     * 2단 폴백:
     *  - 안쪽(모델): primary→2.5-flash→2.5-flash-lite. 503/RPM 은 모델당 3회 재시도(지수 백오프),
     *    429(quota)·400 hard error 는 즉시 다음 모델로.
     *  - 바깥(키): 한 키의 **모든 모델이 quota 소진**됐을 때만 다음 키로. 비-quota(일시 장애 등)면
     *    키를 바꿔도 같은 문제라 여분 키를 낭비하지 않고 즉시 degraded.
     *
     * 한 번 성공하면 lastFailureAt reset (자가 회복 시그널).
     */
    private fun postWithRetry(content: String, timeoutSeconds: Long, chain: List<String>): String? {
        for ((keyIndex, key) in apiKeys.withIndex()) {
            val outcome = tryAllModels(content, timeoutSeconds, key, chain)
            outcome.body?.let { body ->
                if (lastFailureAt != null) {
                    log.info("Gemini recovered via key={} (key idx={})", mask(key), keyIndex)
                    lastFailureAt = null
                }
                return body
            }
            if (!outcome.quotaExhausted) {
                // 키 쿼터 문제가 아님(503/네트워크/hard error) — 여분 키를 태워도 같은 결과라 중단.
                log.error("Gemini key={} 비-quota 실패 — 키 폴백 생략, marking degraded", mask(key))
                lastFailureAt = Instant.now()
                return null
            }
            if (keyIndex < apiKeys.size - 1) {
                log.warn("Gemini key={} 모든 모델 quota 소진 — 다음 키로 폴백", mask(key))
            }
        }
        log.error("Gemini API 모든 키({})·모델 quota 소진 — marking degraded", apiKeys.size)
        lastFailureAt = Instant.now()
        return null
    }

    /** 키 1개로 모델 체인 전부 시도. 성공 body 또는 (실패 시) quota 소진 여부. */
    private fun tryAllModels(content: String, timeoutSeconds: Long, apiKey: String, chain: List<String>): KeyOutcome {
        var sawQuota = false
        for ((modelIndex, m) in chain.withIndex()) {
            when (val r = postToModel(content, timeoutSeconds, m, apiKey)) {
                is ModelResult.Ok -> return KeyOutcome(body = r.body, quotaExhausted = sawQuota)
                ModelResult.Quota -> sawQuota = true
                ModelResult.Failed -> Unit
            }
            if (modelIndex < chain.size - 1) {
                log.warn("Gemini key={} model={} exhausted — trying next model", mask(apiKey), m)
            }
        }
        return KeyOutcome(body = null, quotaExhausted = sawQuota)
    }

    /** 키 1개·모델 1개 시도 결과. */
    private data class KeyOutcome(val body: String?, val quotaExhausted: Boolean)

    private sealed interface ModelResult {
        data class Ok(val body: String) : ModelResult
        /** 429 + body 에 "quota" — 이 키의 해당 모델 일일 한도 소진. */
        object Quota : ModelResult
        /** 503/네트워크/400 등 비-quota 실패. */
        object Failed : ModelResult
    }

    /** 단일 키·단일 모델로 3회 재시도. */
    private fun postToModel(content: String, timeoutSeconds: Long, modelName: String, apiKey: String): ModelResult {
        val url = "$baseUrl/v1beta/models/$modelName:generateContent?key=$apiKey"
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = runCatching {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(content))
                    .build()
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }.getOrElse {
                log.warn("Gemini API call failed key={} model={} (attempt {}/{})", mask(apiKey), modelName, attempt, MAX_ATTEMPTS, it)
                null
            }

            if (result != null && result.statusCode() == 200) return ModelResult.Ok(result.body())

            val status = result?.statusCode() ?: -1
            val body = result?.body()
            val quota = status == 429 && body?.contains("quota", ignoreCase = true) == true
            val retryable = result == null || isRetryable(status, body)
            if (!retryable || attempt == MAX_ATTEMPTS) {
                log.warn("Gemini API key={} model={} giving up. status={} quota={} attempt={}/{} body={}",
                    mask(apiKey), modelName, status, quota, attempt, MAX_ATTEMPTS, body?.take(200))
                return if (quota) ModelResult.Quota else ModelResult.Failed
            }
            val backoffMs = 1000L shl (attempt - 1) // 1s, 2s, 4s
            log.warn("Gemini API key={} model={} status={} — retry {}/{} after {}ms", mask(apiKey), modelName, status, attempt, MAX_ATTEMPTS, backoffMs)
            runCatching { Thread.sleep(backoffMs) }
        }
        return ModelResult.Failed
    }

    /**
     * 재시도 여부 판정. 503 과부하 / 500 내부오류는 일시적이라 재시도.
     * 429 는 두 종류 — RPM 순간 초과(곧 회복)는 재시도하되, **쿼터 소진(body 에 "quota")은
     * 초 단위로 안 풀리므로 재시도하지 않고 즉시 다음 모델로 폴백**한다. (재시도 폭주 → 9배 증폭 방지)
     * visibility=internal: 회귀 테스트(GeminiClientTest)용.
     */
    internal fun isRetryable(status: Int, body: String?): Boolean {
        if (status == 429 && body?.contains("quota", ignoreCase = true) == true) return false
        return status in RETRYABLE_STATUSES
    }

    private fun buildPayload(
        prompt: String,
        maxOutputTokens: Int = 2048,
        jsonOutput: Boolean = true,
        disableThinking: Boolean = true,
    ): String {
        val root = objectMapper.createObjectNode()
        val contents = root.putArray("contents")
        val msg = contents.addObject()
        val parts = msg.putArray("parts")
        parts.addObject().put("text", prompt)

        val gen = root.putObject("generationConfig")
        gen.put("temperature", 0.3)
        // 평문 섹션 리포트(PRO 심층 리포트)는 JSON 강제 대신 평문 — 줄바꿈 보존.
        if (jsonOutput) gen.put("responseMimeType", "application/json")
        gen.put("maxOutputTokens", maxOutputTokens)
        // Gemini 2.5 flash 는 기본 thinking 모드라 응답 parts 가 thought + answer 로 쪼개진다.
        // thinkingBudget=0 으로 thinking 을 끄면 항상 단일 parts 가 와서 파싱이 단순해진다.
        // 단 상위 모델(2.5-pro)은 thinking 비활성화를 거부 → disableThinking=false 로 두고
        // plainText 파서가 thought 파트를 걸러낸다.
        if (disableThinking) gen.putObject("thinkingConfig").put("thinkingBudget", 0)

        return objectMapper.writeValueAsString(root)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        // 503 과부하 / 500 내부오류 / 429 RPM 순간초과 — 일시적이라 재시도 가치 있음.
        // 단 429 라도 body 에 "quota"(일일 한도 소진)면 isRetryable 에서 제외 → 즉시 폴백.
        private val RETRYABLE_STATUSES = setOf(429, 500, 503)
    }
}

data class MarketInsightAnalysis(
    val headline: String,
    val summary: String,
    val sentiment: MediaSentiment,
    val keyPoints: List<String>,
)

data class AiPicksAnalysis(
    val summary: String,
    val picks: List<AiPick>,
)

/** 급등락 사유 생성 입력 — 종목 + 매칭된 헤드라인. */
data class MoverReasonInput(
    val market: String,
    val ticker: String,
    val name: String,
    val changeRate: Double,
    val direction: String,
    val headlines: List<String>,
)

/** 급등락 사유 생성 결과 — 종목별 한 줄 사유. */
data class MoverReasonAnalysis(
    val ticker: String,
    val reason: String,
)
