package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Supadata 자막 추출 API 클라이언트 — YouTube 자막(POT 토큰)을 직접 못 뚫어서 대행 사용.
 *
 * GET {base}/transcript?url=...&text=true&mode=native&lang=ko  (header: x-api-key)
 *  - 200: 즉시 결과(content 문자열)
 *  - 202: { jobId } → {base}/transcript/{jobId} 폴링
 *  - mode=native: 기존 자막만(Whisper AI 생성 비용 회피). 자막 없으면 실패 → null.
 *
 * api-key 비어있으면 isEnabled()=false → 유튜브 흐름 요약 전체가 비활성(데이터 기반 흐름은 그대로).
 */
@Component
class SupadataTranscriptClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.supadata.api-key:}") private val apiKey: String,
    @Value("\${signal-desk.integrations.supadata.base-url:https://api.supadata.ai/v1}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun isEnabled(): Boolean = apiKey.isNotBlank()

    /** 영상 자막 평문. 실패/비활성/자막없음 시 null. maxChars 로 길이 상한(Gemini 토큰 보호). */
    fun fetchTranscript(videoUrl: String, maxChars: Int = 24000): String? {
        if (!isEnabled()) return null
        return runCatching {
            val enc = URLEncoder.encode(videoUrl, StandardCharsets.UTF_8)
            val url = "$baseUrl/transcript?url=$enc&text=true&mode=native&lang=ko"
            val res = get(url)
            val text = when (res.statusCode()) {
                200 -> extractContent(objectMapper.readTree(res.body()))
                202 -> pollJob(objectMapper.readTree(res.body()).get("jobId")?.asText())
                else -> {
                    log.warn("Supadata transcript HTTP {} — {}", res.statusCode(), res.body()?.take(200))
                    null
                }
            }
            text?.trim()?.takeIf { it.isNotBlank() }?.take(maxChars)
        }.getOrElse {
            log.warn("Supadata transcript failed for {}", videoUrl, it)
            null
        }
    }

    private fun pollJob(jobId: String?): String? {
        if (jobId.isNullOrBlank()) return null
        repeat(12) { attempt ->
            Thread.sleep(if (attempt == 0) 2000 else 5000)
            val res = runCatching { get("$baseUrl/transcript/$jobId") }.getOrNull() ?: return@repeat
            if (res.statusCode() != 200) return@repeat
            val node = objectMapper.readTree(res.body())
            when (node.get("status")?.asText()) {
                "completed" -> return extractContent(node)
                "failed" -> { log.warn("Supadata job {} failed", jobId); return null }
                else -> Unit // queued/active → 계속 폴링
            }
        }
        log.warn("Supadata job {} timed out", jobId)
        return null
    }

    /** content 가 text=true 면 문자열, 아니면 세그먼트 배열({text}) — 둘 다 흡수. */
    private fun extractContent(node: JsonNode): String? {
        val content = node.get("content") ?: return null
        return when {
            content.isTextual -> content.asText()
            content.isArray -> content.joinToString(" ") { it.get("text")?.asText().orEmpty() }
            else -> null
        }
    }

    private fun get(url: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .GET()
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
