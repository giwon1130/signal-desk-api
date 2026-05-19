package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.giwon.signaldesk.features.market.application.UsIndicesSnapshot
import com.giwon.signaldesk.features.market.application.VixSnapshot
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

    fun summarize(videoTitle: String, transcript: String): MediaSummaryAnalysis? =
        callJson(buildVideoPrompt(videoTitle, transcript))

    fun summarizeMarketInsight(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        headlines: List<com.giwon.signaldesk.features.market.application.MarketNews>,
        upcomingEvents: List<com.giwon.signaldesk.features.events.application.MarketEvent> = emptyList(),
    ): MarketInsightAnalysis? = callInsightJson(buildMarketInsightPrompt(vix, indices, headlines, upcomingEvents))

    /**
     * 모닝 브리프 — 야간 미국장 결과 + KR 뉴스 + 보유/관심 종목 공시를 합쳐
     * 장 시작 전(08:30 KST) 한국 개인 투자자가 오늘 대응을 준비할 수 있게 종합한다.
     */
    fun summarizeMorningBrief(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        headlines: List<com.giwon.signaldesk.features.market.application.MarketNews>,
        disclosureTitles: List<String>,
        upcomingEvents: List<com.giwon.signaldesk.features.events.application.MarketEvent> = emptyList(),
    ): MarketInsightAnalysis? = callInsightJson(
        buildMorningBriefPrompt(vix, indices, headlines, disclosureTitles, upcomingEvents),
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
    ): MediaSummaryAnalysis? = callJson(buildNewsDigestPrompt(marketLabel, dateLabel, headlines))

    private fun callInsightJson(prompt: String): MarketInsightAnalysis? {
        if (!isEnabled()) {
            log.warn("GeminiClient disabled — GEMINI_API_KEY 미설정")
            return null
        }
        val content = buildPayload(prompt)
        return runCatching {
            val url = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(content))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("Gemini API status={} body={}", response.statusCode(), response.body().take(500))
                return@runCatching null
            }
            parseInsightResponse(response.body())
        }.getOrElse {
            log.warn("Gemini market insight call failed", it)
            null
        }
    }

    private fun callJson(prompt: String): MediaSummaryAnalysis? {
        if (!isEnabled()) {
            log.warn("GeminiClient disabled — GEMINI_API_KEY 미설정")
            return null
        }
        val content = buildPayload(prompt)
        return runCatching {
            val url = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(content))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("Gemini API status={} body={}", response.statusCode(), response.body().take(500))
                return@runCatching null
            }
            parseResponse(response.body())
        }.getOrElse {
            log.warn("Gemini API call failed", it)
            null
        }
    }

    private fun buildVideoPrompt(videoTitle: String, transcript: String): String {
        // 자막이 너무 길면 토큰 비용/응답 시간 폭증 → 앞 80,000자로 자른다 (대략 50K 토큰).
        val safe = if (transcript.length > 80_000) transcript.substring(0, 80_000) else transcript
        val source = if (safe.isBlank()) "(자막 없음 — 영상 제목만으로 추정)" else safe

        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            아래는 데일리 증시 분석 유튜브 방송의 자막(또는 제목)입니다.
            방송 내용을 분석해서 한국 개인 투자자가 빠르게 흐름을 파악할 수 있도록
            아래 JSON 스키마에 맞춰 한국어로 답변하세요.

            스키마:
            {
              "summary": "3~5문장으로 오늘 방송의 핵심 내용을 요약 (각 문장은 줄바꿈으로 구분)",
              "flowAnalysis": "2~3문장으로 시장 흐름 해석 — 강세/약세/관망의 이유와 주목할 섹터",
              "keyTickers": ["방송에서 언급된 종목명 또는 티커. 한국 종목은 6자리 코드(예: 005930), 미국 종목은 티커(예: NVDA) 사용. 최대 6개"],
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나"
            }

            영상 제목: $videoTitle

            방송 내용:
            $source
        """.trimIndent()
    }

    private fun buildNewsDigestPrompt(
        marketLabel: String,
        dateLabel: String,
        headlines: List<Triple<String, String, String>>,
    ): String {
        // 헤드라인이 너무 많으면 토큰 폭증 → 최신 60건으로 제한.
        val capped = headlines.take(60)
        val lines = capped.joinToString("\n") { (source, title, _) -> "- [$source] $title" }
        val marketKo = if (marketLabel == "KR") "한국" else "미국"
        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            아래는 $dateLabel 자 $marketKo 시장 관련 뉴스 헤드라인 묶음입니다 (${capped.size}건).
            여러 매체의 헤드라인을 종합해 한국 개인 투자자가 한 눈에 시황을 파악할 수 있도록
            아래 JSON 스키마에 맞춰 한국어로 답변하세요.

            스키마:
            {
              "summary": "3~5문장으로 오늘 시장의 핵심 흐름 요약 (각 문장은 줄바꿈으로 구분)",
              "flowAnalysis": "2~3문장으로 시장 흐름 해석 — 강세/약세/관망의 이유와 주목할 섹터",
              "keyTickers": ["헤드라인에서 반복 언급된 종목명 또는 티커. 한국 종목은 6자리 코드(예: 005930), 미국 종목은 티커(예: NVDA). 최대 6개"],
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나"
            }

            헤드라인:
            $lines
        """.trimIndent()
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

    private fun parseResponse(body: String): MediaSummaryAnalysis? {
        val root = objectMapper.readTree(body)
        val candidate = root["candidates"]?.firstOrNull() ?: run {
            log.warn("Gemini response missing candidates. body={}", body.take(500))
            return null
        }
        // parts 가 여러 개로 쪼개질 수 있어 (thinking 모델), thought=true 가 아닌 parts 의 text 를 모두 합친다.
        val parts = candidate["content"]?.get("parts") ?: run {
            log.warn("Gemini response missing parts. finishReason={} candidate={}",
                candidate["finishReason"]?.asText(), candidate.toString().take(500))
            return null
        }
        val text = buildString {
            parts.forEach { part ->
                if (part["thought"]?.asBoolean() == true) return@forEach
                part["text"]?.asText()?.let { append(it) }
            }
        }.trim()
        if (text.isBlank()) return null
        val payload = runCatching { objectMapper.readTree(text) }
            .getOrElse {
                log.warn("Gemini response not JSON. raw={}", text.take(500))
                return null
            } as? ObjectNode ?: return null

        val sentimentRaw = payload["sentiment"]?.asText()?.uppercase().orEmpty()
        val sentiment = runCatching { MediaSentiment.valueOf(sentimentRaw) }.getOrDefault(MediaSentiment.NEUTRAL)

        val tickers = payload["keyTickers"]?.mapNotNull { it.asText()?.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.take(6)
            ?: emptyList()

        return MediaSummaryAnalysis(
            summary = payload["summary"]?.asText().orEmpty().trim(),
            flowAnalysis = payload["flowAnalysis"]?.asText().orEmpty().trim(),
            keyTickers = tickers,
            sentiment = sentiment,
        )
    }

    private fun buildMarketInsightPrompt(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        headlines: List<com.giwon.signaldesk.features.market.application.MarketNews>,
        upcomingEvents: List<com.giwon.signaldesk.features.events.application.MarketEvent>,
    ): String {
        val capped = headlines.take(25)
        val headlineLines = capped.joinToString("\n") { n -> "- [${n.source}] ${n.title}" }
        val vixLine = if (vix != null) "VIX(공포지수): ${vix.currentPrice} (변화: ${vix.priceChange})" else "VIX: 데이터 없음"
        val nasdaqLine = if (indices?.nasdaq != null) "NASDAQ: ${indices.nasdaq.currentValue} (${indices.nasdaq.changeRate}%)" else "NASDAQ: 데이터 없음"
        val sp500Line = if (indices?.sp500 != null) "S&P500: ${indices.sp500.currentValue} (${indices.sp500.changeRate}%)" else "S&P500: 데이터 없음"
        val eventsBlock = if (upcomingEvents.isNotEmpty()) {
            val lines = upcomingEvents.take(8).joinToString("\n") { e ->
                val time = e.time?.let { " $it" } ?: ""
                "- [${e.date}$time · ${e.market}] ${e.title}${e.description?.let { " — $it" } ?: ""}"
            }
            "\n            === 다가오는 주요 이벤트 (3일내) ===\n            $lines"
        } else ""

        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            아래 시장 지표와 뉴스 헤드라인을 종합해 한국 개인 투자자를 위한 오늘의 종합 시장 인사이트를 작성하세요.

            === 시장 지표 ===
            $vixLine
            $nasdaqLine
            $sp500Line
$eventsBlock
            === 오늘 주요 뉴스 헤드라인 (${capped.size}건) ===
            $headlineLines

            아래 JSON 스키마로 한국어 답변:
            {
              "headline": "오늘 시장을 한 줄로 압축 (20자 이내, 핵심 키워드 포함)",
              "summary": "2~3문장 종합 분석. VIX·지수·뉴스와 다가오는 이벤트(있다면)를 연결해 지금 시장 분위기와 개인 투자자 행동 포인트를 설명",
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나",
              "keyPoints": ["주목할 포인트 최대 3가지. 각 20자 이내. 다가오는 큰 이벤트가 있으면 1개는 그것에 할당"]
            }
        """.trimIndent()
    }

    private fun buildMorningBriefPrompt(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        headlines: List<com.giwon.signaldesk.features.market.application.MarketNews>,
        disclosureTitles: List<String>,
        upcomingEvents: List<com.giwon.signaldesk.features.events.application.MarketEvent>,
    ): String {
        val capped = headlines.take(20)
        val headlineLines = capped.joinToString("\n") { n -> "- [${n.source}] ${n.title}" }
        val vixLine = if (vix != null) "VIX(공포지수): ${vix.currentPrice} (변화: ${vix.priceChange})" else "VIX: 데이터 없음"
        val nasdaqLine = if (indices?.nasdaq != null) "NASDAQ: ${indices.nasdaq.currentValue} (${indices.nasdaq.changeRate}%)" else "NASDAQ: 데이터 없음"
        val sp500Line = if (indices?.sp500 != null) "S&P500: ${indices.sp500.currentValue} (${indices.sp500.changeRate}%)" else "S&P500: 데이터 없음"
        val disclosureBlock = if (disclosureTitles.isNotEmpty()) {
            val lines = disclosureTitles.take(15).joinToString("\n") { "- $it" }
            "\n            === 사용자 보유/관심 종목의 어젯밤 ~ 오늘 아침 공시 (${disclosureTitles.size}건) ===\n            $lines"
        } else ""
        val eventsBlock = if (upcomingEvents.isNotEmpty()) {
            val lines = upcomingEvents.take(5).joinToString("\n") { e ->
                val time = e.time?.let { " $it" } ?: ""
                "- [${e.date}$time · ${e.market}] ${e.title}${e.description?.let { " — $it" } ?: ""}"
            }
            "\n            === 다가오는 주요 이벤트 (3일내) ===\n            $lines"
        } else ""

        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            지금은 한국 장 시작 30분 전(08:30 KST). 한국 개인 투자자가 '오늘 장을 어떻게 대응할지'
            준비할 수 있도록 야간 미국장 결과 + 한국 뉴스 + 보유/관심 종목 공시를 종합해 브리핑하세요.

            === 야간 미국장 ===
            $vixLine
            $nasdaqLine
            $sp500Line
$disclosureBlock$eventsBlock
            === 오늘 한국·미국 시장 뉴스 헤드라인 (${capped.size}건) ===
            $headlineLines

            아래 JSON 스키마로 한국어 답변:
            {
              "headline": "오늘 장의 핵심을 한 줄로 (20자 이내, 매수/관망/방어 같은 액션 키워드 포함)",
              "summary": "3~4문장. 야간 미국장 → 한국장 영향 → 보유 종목 공시 → 오늘 대응 포인트 순으로 연결. 마지막 문장에 '오늘 행동' 명시",
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나",
              "keyPoints": ["오늘 봐야 할 포인트 3개. 각 25자 이내. 공시·이벤트 우선 반영"]
            }
        """.trimIndent()
    }

    private fun parseInsightResponse(body: String): MarketInsightAnalysis? {
        val root = objectMapper.readTree(body)
        val candidate = root["candidates"]?.firstOrNull() ?: return null
        val parts = candidate["content"]?.get("parts") ?: return null
        val text = buildString {
            parts.forEach { part ->
                if (part["thought"]?.asBoolean() == true) return@forEach
                part["text"]?.asText()?.let { append(it) }
            }
        }.trim()
        if (text.isBlank()) return null
        val payload = runCatching { objectMapper.readTree(text) }
            .getOrElse {
                log.warn("Gemini insight response not JSON. raw={}", text.take(500))
                return null
            } as? ObjectNode ?: return null

        val sentimentRaw = payload["sentiment"]?.asText()?.uppercase().orEmpty()
        val sentiment = runCatching { MediaSentiment.valueOf(sentimentRaw) }.getOrDefault(MediaSentiment.NEUTRAL)
        val keyPoints = payload["keyPoints"]?.mapNotNull { it.asText()?.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return MarketInsightAnalysis(
            headline = payload["headline"]?.asText().orEmpty().trim(),
            summary = payload["summary"]?.asText().orEmpty().trim(),
            sentiment = sentiment,
            keyPoints = keyPoints,
        )
    }
}

data class MarketInsightAnalysis(
    val headline: String,
    val summary: String,
    val sentiment: MediaSentiment,
    val keyPoints: List<String>,
)
