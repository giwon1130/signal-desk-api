package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.giwon.signaldesk.features.ai.application.AiPick
import org.slf4j.LoggerFactory

/**
 * Gemini API 응답 파서. 3종 응답 타입(MediaSummaryAnalysis / MarketInsightAnalysis / AiPicksAnalysis)
 * 각각의 JSON 추출 로직만 담당. HTTP 호출은 [GeminiClient], 프롬프트는 [GeminiPrompts] 참조.
 *
 * 응답 구조 공통:
 *   candidates[0].content.parts[] — parts 는 thinking 모델에서 thought=true 와 본문이 섞임.
 *   thought 가 아닌 parts 의 text 만 합쳐서 JSON 으로 파싱한다.
 */
internal object GeminiResponseParsing {
    private val log = LoggerFactory.getLogger(javaClass)

    fun newsDigest(body: String, mapper: ObjectMapper): MediaSummaryAnalysis? {
        val payload = extractJson(body, mapper, label = "news digest") ?: return null
        val sentiment = parseSentiment(payload)
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

    fun insight(body: String, mapper: ObjectMapper): MarketInsightAnalysis? {
        val payload = extractJson(body, mapper, label = "insight") ?: return null
        val sentiment = parseSentiment(payload)
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

    fun moverReasons(body: String, mapper: ObjectMapper): List<MoverReasonAnalysis> {
        val payload = extractJson(body, mapper, label = "mover reasons") ?: return emptyList()
        return payload["reasons"]?.mapNotNull { node ->
            val ticker = node["ticker"]?.asText()?.trim().orEmpty()
            val reason = node["reason"]?.asText()?.trim().orEmpty()
            if (ticker.isBlank() || reason.isBlank()) null else MoverReasonAnalysis(ticker, reason)
        } ?: emptyList()
    }

    fun aiPicks(body: String, mapper: ObjectMapper): AiPicksAnalysis? {
        val payload = extractJson(body, mapper, label = "ai picks") ?: return null
        val picks = payload["picks"]?.mapNotNull { node ->
            val ticker = node["ticker"]?.asText()?.trim().orEmpty()
            if (ticker.isBlank()) return@mapNotNull null
            AiPick(
                market = "KR",  // 호출부에서 후보 기준으로 보정
                ticker = ticker,
                name = node["name"]?.asText().orEmpty().trim(),  // 호출부에서 후보 기준으로 최종 보정
                reason = node["reason"]?.asText().orEmpty().trim(),
                expectedReturnRate = node["expectedReturnRate"]?.asDouble(),
                confidence = node["confidence"]?.asInt()?.coerceIn(0, 100) ?: 50,
                riskNote = node["riskNote"]?.asText().orEmpty().trim(),
            )
        } ?: emptyList()
        return AiPicksAnalysis(
            summary = payload["summary"]?.asText().orEmpty().trim(),
            picks = picks,
        )
    }

    /**
     * candidates[0].content.parts[] 에서 thought=true 가 아닌 text 들을 합쳐 JSON 으로 파싱.
     * @param label 로깅용 — "insight"/"news digest"/"ai picks"
     */
    private fun extractJson(body: String, mapper: ObjectMapper, label: String): ObjectNode? {
        val root = mapper.readTree(body)
        val candidate = root["candidates"]?.firstOrNull() ?: run {
            log.warn("Gemini $label response missing candidates. body={}", body.take(500))
            return null
        }
        val parts = candidate["content"]?.get("parts") ?: run {
            log.warn("Gemini $label response missing parts. finishReason={} candidate={}",
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
        return runCatching { mapper.readTree(text) }
            .getOrElse {
                log.warn("Gemini $label response not JSON. raw={}", text.take(500))
                return null
            } as? ObjectNode
    }

    private fun parseSentiment(payload: JsonNode): MediaSentiment {
        val raw = payload["sentiment"]?.asText()?.uppercase().orEmpty()
        return runCatching { MediaSentiment.valueOf(raw) }.getOrDefault(MediaSentiment.NEUTRAL)
    }
}
