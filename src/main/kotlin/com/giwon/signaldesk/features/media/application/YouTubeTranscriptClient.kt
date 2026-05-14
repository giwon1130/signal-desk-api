package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 유튜브 자동 자막을 비공식 timedtext API로 가져온다 (API 키 불필요).
 *
 * 자막 종류:
 *   - lang=ko : 한국어 (수동/자동)
 *   - kind=asr : 자동 생성 자막
 *
 * 실패 시나리오:
 *   - 자막 자체가 없는 경우 → empty 반환 → 호출자가 제목/설명 fallback
 *   - 비공식 API라 가끔 429/403 → 그날은 skip
 */
@Component
class YouTubeTranscriptClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.youtube.transcript-base-url}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /** 자막을 평문 문자열로 반환. 자막 없으면 빈 문자열. */
    fun fetchTranscript(videoId: String): String {
        if (videoId.isBlank()) return ""
        // 1차: 수동 자막 (lang=ko)
        val manual = tryFetch(videoId, asr = false)
        if (manual.isNotBlank()) return manual
        // 2차: 자동 자막 (kind=asr)
        return tryFetch(videoId, asr = true)
    }

    private fun tryFetch(videoId: String, asr: Boolean): String {
        return runCatching {
            val asrParam = if (asr) "&kind=asr" else ""
            val url = "$baseUrl?v=$videoId&lang=ko&fmt=json3$asrParam"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || response.body().isBlank()) {
                return@runCatching ""
            }
            extractText(response.body())
        }.getOrElse {
            log.debug("transcript fetch failed videoId={} asr={}", videoId, asr, it)
            ""
        }
    }

    /**
     * json3 응답: {"events":[{"segs":[{"utf8":"안녕"},{"utf8":" 하세요"}]}, ...]}
     * 세그먼트 utf8 을 이어붙여 평문 한 덩어리로.
     */
    private fun extractText(body: String): String {
        val root = objectMapper.readTree(body)
        val events = root["events"] ?: return ""
        val sb = StringBuilder()
        events.forEach { event ->
            val segs = event["segs"] ?: return@forEach
            segs.forEach { seg ->
                seg["utf8"]?.asText()?.let { sb.append(it) }
            }
            sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
