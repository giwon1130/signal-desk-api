package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 유튜브 자막을 innertube(비공식) 방식으로 가져온다 (API 키 불필요).
 *
 * 흐름:
 *   1) https://www.youtube.com/watch?v=VIDEO_ID HTML 다운로드
 *   2) HTML 안의 `ytInitialPlayerResponse = {...};` JSON 추출
 *   3) captions.playerCaptionsTracklistRenderer.captionTracks 배열에서 한국어 트랙 찾기
 *   4) 해당 트랙의 baseUrl 에 &fmt=json3 붙여서 자막 다운로드
 *   5) json3 events.segs.utf8 이어붙이기
 *
 * YouTube 가 timedtext API 를 직접 요청하면 비어있게 응답하지만,
 * watch 페이지에서 받은 baseUrl(서명/타임스탬프 포함) 로 요청하면 정상 동작.
 */
@Component
class YouTubeTranscriptClient(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val webUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    // TVHTML5_SIMPLY_EMBEDDED_PLAYER 는 키 없이도 동작하고 봇 차단에 가장 강한 경로.
    private val tvUserAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/13.0 Safari/605.1.15"

    /** 자막을 평문 문자열로 반환. 없으면 빈 문자열. */
    fun fetchTranscript(videoId: String): String {
        if (videoId.isBlank()) return ""
        return runCatching {
            // 1차: innertube iOS 클라이언트 (서버 IP 환경에서 가장 안정적)
            var tracks = fetchCaptionTracksInnertube(videoId)
            if (tracks.isEmpty()) {
                // 2차 fallback: watch 페이지 HTML 파싱 (로컬 개발/일반 IP 에서 잘 됨)
                tracks = fetchCaptionTracksFromWatch(videoId)
            }
            if (tracks.isEmpty()) {
                log.info("no caption tracks. videoId={}", videoId)
                return@runCatching ""
            }
            val track = tracks.firstOrNull { it.languageCode.startsWith("ko") } ?: tracks.first()
            downloadCaption(track.baseUrl).also {
                log.info("transcript ok. videoId={}, lang={}, chars={}", videoId, track.languageCode, it.length)
            }
        }.getOrElse {
            log.warn("transcript fetch failed videoId={}", videoId, it)
            ""
        }
    }

    private data class CaptionTrack(val baseUrl: String, val languageCode: String, val kind: String?)

    /**
     * YouTube 내부 player API(innertube) — TVHTML5_SIMPLY_EMBEDDED_PLAYER 클라이언트.
     * 이 클라이언트는 키 없이도 받아주고, 서버 IP 의 봇 차단을 가장 잘 우회한다.
     * yt-dlp 가 fallback 으로 쓰는 패턴.
     */
    private fun fetchCaptionTracksInnertube(videoId: String): List<CaptionTrack> {
        val payload = """
            {"context":{"client":{"clientName":"TVHTML5_SIMPLY_EMBEDDED_PLAYER","clientVersion":"2.0","hl":"ko","gl":"KR"}},"videoId":"$videoId"}
        """.trimIndent()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://www.youtube.com/youtubei/v1/player"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("User-Agent", tvUserAgent)
            .header("X-YouTube-Client-Name", "85")
            .header("X-YouTube-Client-Version", "2.0")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("innertube player status={} body={} videoId={}",
                response.statusCode(), response.body().take(300), videoId)
            return emptyList()
        }
        return parseCaptionTracks(response.body())
    }

    private fun fetchCaptionTracksFromWatch(videoId: String): List<CaptionTrack> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://www.youtube.com/watch?v=$videoId&hl=ko"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", webUserAgent)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.warn("watch page status={} videoId={}", response.statusCode(), videoId)
            return emptyList()
        }
        val html = response.body()
        val playerJson = extractPlayerResponse(html) ?: return emptyList()
        return parseCaptionTracks(playerJson)
    }

    /**
     * HTML 에서 `ytInitialPlayerResponse = {...};` 의 JSON 객체만 잘라낸다.
     * 정규식으로 끝을 찾기 어려운 큰 객체라 brace counting 으로 잘라야 한다.
     */
    private fun extractPlayerResponse(html: String): String? {
        val key = "ytInitialPlayerResponse"
        val keyIdx = html.indexOf(key)
        if (keyIdx < 0) return null
        // "key = {" 위치에서 '{' 찾기
        val braceStart = html.indexOf('{', keyIdx)
        if (braceStart < 0) return null

        var depth = 0
        var inString = false
        var escape = false
        var i = braceStart
        while (i < html.length) {
            val c = html[i]
            if (escape) { escape = false; i++; continue }
            if (c == '\\' && inString) { escape = true; i++; continue }
            if (c == '"') inString = !inString
            else if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return html.substring(braceStart, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun parseCaptionTracks(playerJson: String): List<CaptionTrack> {
        val root = objectMapper.readTree(playerJson)
        val tracks = root["captions"]?.get("playerCaptionsTracklistRenderer")?.get("captionTracks")
            ?: return emptyList()
        return tracks.mapNotNull { node ->
            val baseUrl = node["baseUrl"]?.asText() ?: return@mapNotNull null
            val lang = node["languageCode"]?.asText().orEmpty()
            val kind = node["kind"]?.asText()
            CaptionTrack(baseUrl, lang, kind)
        }
    }

    private fun downloadCaption(baseUrl: String): String {
        val urlWithFmt = if (baseUrl.contains("fmt=")) baseUrl else "$baseUrl&fmt=json3"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(urlWithFmt))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", webUserAgent)
            .GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200 || response.body().isBlank()) return ""
        return extractTextFromJson3(response.body())
    }

    /** json3: {"events":[{"segs":[{"utf8":"안녕"},{"utf8":" 하세요"}]}, ...]} */
    private fun extractTextFromJson3(body: String): String {
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
