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
    @Value("\${signal-desk.integrations.gemini.base-url}") private val baseUrl: String,
    @Value("\${signal-desk.integrations.gemini.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    // Fallback chain: 주 모델 503/429 시 다음 모델 시도. 현행 정식 GA 모델로 구성.
    // primary 가 fallback 과 중복되면 distinct 로 제거.
    private val modelChain: List<String> =
        listOf(model, "gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite").distinct()

    // 시스템 헬스 — 마지막 모든 모델 실패 시각. 사용자에게 "일시 장애" 안내용.
    // 성공 호출이 한 번이라도 들어오면 자가 회복 — null 로 reset.
    @Volatile private var lastFailureAt: Instant? = null

    fun isEnabled(): Boolean = apiKey.isNotBlank()

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
        return postWithRetry(prompt, timeoutSeconds)
    }

    /**
     * Gemini generateContent 호출 + 재시도 + 모델 fallback chain.
     *
     * 흐름:
     *  1. primary model 3회 재시도 (지수 백오프 1→2→4초)
     *  2. 다 실패 시 다음 모델로 (gemini-2.0-flash-exp)
     *  3. 또 실패 시 다음 (gemini-1.5-flash)
     *  4. 모든 모델 실패 → lastFailureAt set, null 반환
     *
     * 한 번 성공하면 lastFailureAt reset (자가 회복 시그널).
     * 503/429/500 은 재시도, 400 같은 hard error 는 즉시 다음 모델로.
     */
    private fun postWithRetry(prompt: String, timeoutSeconds: Long): String? {
        val content = buildPayload(prompt)
        for ((modelIndex, m) in modelChain.withIndex()) {
            val body = postToModel(content, timeoutSeconds, m)
            if (body != null) {
                // 성공 — 자가 회복.
                if (lastFailureAt != null) {
                    log.info("Gemini recovered via model={} (chain idx={})", m, modelIndex)
                    lastFailureAt = null
                }
                return body
            }
            if (modelIndex < modelChain.size - 1) {
                log.warn("Gemini model {} exhausted — trying next fallback", m)
            }
        }
        log.error("Gemini API all {} models exhausted — marking degraded", modelChain.size)
        lastFailureAt = Instant.now()
        return null
    }

    /** 단일 모델로 3회 재시도. 성공 body or null. */
    private fun postToModel(content: String, timeoutSeconds: Long, modelName: String): String? {
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
                log.warn("Gemini API call failed model={} (attempt {}/{})", modelName, attempt, MAX_ATTEMPTS, it)
                null
            }

            if (result != null && result.statusCode() == 200) return result.body()

            val status = result?.statusCode() ?: -1
            val retryable = result == null || status in RETRYABLE_STATUSES
            if (!retryable || attempt == MAX_ATTEMPTS) {
                log.warn("Gemini API model={} giving up. status={} attempt={}/{} body={}",
                    modelName, status, attempt, MAX_ATTEMPTS, result?.body()?.take(200))
                return null
            }
            val backoffMs = 1000L shl (attempt - 1) // 1s, 2s, 4s
            log.warn("Gemini API model={} status={} — retry {}/{} after {}ms", modelName, status, attempt, MAX_ATTEMPTS, backoffMs)
            runCatching { Thread.sleep(backoffMs) }
        }
        return null
    }

    private fun buildPayload(prompt: String): String {
        val root = objectMapper.createObjectNode()
        val contents = root.putArray("contents")
        val msg = contents.addObject()
        val parts = msg.putArray("parts")
        parts.addObject().put("text", prompt)

        val gen = root.putObject("generationConfig")
        gen.put("temperature", 0.3)
        gen.put("responseMimeType", "application/json")
        gen.put("maxOutputTokens", 2048)
        // Gemini 2.5 flash 는 기본 thinking 모드라 응답 parts 가 thought + answer 로 쪼개진다.
        // thinkingBudget=0 으로 thinking 을 끄면 항상 단일 JSON parts 가 와서 파싱이 단순해진다.
        gen.putObject("thinkingConfig").put("thinkingBudget", 0)

        return objectMapper.writeValueAsString(root)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        // 503 과부하 / 429 쿼터 / 500 내부오류 — 일시적이라 재시도 가치 있음.
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
