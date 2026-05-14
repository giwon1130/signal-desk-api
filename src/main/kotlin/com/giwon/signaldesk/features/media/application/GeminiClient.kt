package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

    fun summarize(videoTitle: String, transcript: String): MediaSummaryAnalysis? {
        if (!isEnabled()) {
            log.warn("GeminiClient disabled — GEMINI_API_KEY 미설정")
            return null
        }

        val content = buildContent(videoTitle, transcript)
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

    private fun buildContent(videoTitle: String, transcript: String): String {
        // 자막이 너무 길면 토큰 비용/응답 시간 폭증 → 앞 80,000자로 자른다 (대략 50K 토큰).
        val safe = if (transcript.length > 80_000) transcript.substring(0, 80_000) else transcript
        val source = if (safe.isBlank()) "(자막 없음 — 영상 제목만으로 추정)" else safe

        val prompt = """
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

        val root = objectMapper.createObjectNode()
        val contents = root.putArray("contents")
        val msg = contents.addObject()
        val parts = msg.putArray("parts")
        parts.addObject().put("text", prompt)

        val gen = root.putObject("generationConfig")
        gen.put("temperature", 0.3)
        gen.put("responseMimeType", "application/json")
        gen.put("maxOutputTokens", 1024)

        return objectMapper.writeValueAsString(root)
    }

    private fun parseResponse(body: String): MediaSummaryAnalysis? {
        val root = objectMapper.readTree(body)
        val text = root["candidates"]?.firstOrNull()
            ?.get("content")?.get("parts")?.firstOrNull()
            ?.get("text")?.asText()
            ?: return null
        val payload = objectMapper.readTree(text) as? ObjectNode ?: return null

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
}
