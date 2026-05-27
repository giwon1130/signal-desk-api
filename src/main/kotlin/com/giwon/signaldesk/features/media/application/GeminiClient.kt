package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.ai.application.AiPick
import com.giwon.signaldesk.features.ai.application.PickCandidate
import com.giwon.signaldesk.features.events.application.MarketEvent
import com.giwon.signaldesk.features.market.application.InvestorFlowSnapshot
import com.giwon.signaldesk.features.market.application.MacroSnapshot
import com.giwon.signaldesk.features.market.application.MarketNews
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

/**
 * Google Gemini API (generativeContent) 클라이언트.
 *
 * 무료 한도(2026.05 기준): gemini-2.0-flash-exp 15 req/분, 1500 req/일, 100만 토큰/일.
 * 데일리 방송 1회 요약은 자막 2~5만 토큰 수준 → 무료 한도로 충분.
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

    fun isEnabled(): Boolean = apiKey.isNotBlank()

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
    ): MarketInsightAnalysis? = callInsight(
        GeminiPrompts.morningBrief(vix, indices, macro, headlines, disclosureTitles, investorFlow, upcomingEvents),
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
     * Gemini generateContent 호출 + 재시도. 성공 시 응답 body, 실패 시 null.
     *
     * 503(UNAVAILABLE, 모델 과부하) / 429(쿼터) / 500 은 일시적이라 지수 백오프(1s→2s→4s)로
     * 최대 [MAX_ATTEMPTS]회 재시도. 08:30 모닝 브리프 자동 트리거가 1회뿐이라 한 번의 503에
     * 그날 브리프가 통째로 날아가는 것을 막는다. 400 등 비재시도 상태는 즉시 종료.
     */
    private fun postWithRetry(prompt: String, timeoutSeconds: Long): String? {
        val content = buildPayload(prompt)
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"
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
                log.warn("Gemini API call failed (attempt {}/{})", attempt, MAX_ATTEMPTS, it)
                null
            }

            if (result != null && result.statusCode() == 200) return result.body()

            val status = result?.statusCode() ?: -1
            val retryable = result == null || status in RETRYABLE_STATUSES
            if (!retryable || attempt == MAX_ATTEMPTS) {
                log.warn("Gemini API giving up. status={} attempt={}/{} body={}",
                    status, attempt, MAX_ATTEMPTS, result?.body()?.take(300))
                return null
            }
            val backoffMs = 1000L shl (attempt - 1) // 1s, 2s, 4s
            log.warn("Gemini API status={} — retry {}/{} after {}ms", status, attempt, MAX_ATTEMPTS, backoffMs)
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
