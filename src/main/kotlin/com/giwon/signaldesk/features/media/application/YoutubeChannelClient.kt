package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * YouTube 채널 RSS — API 키 없이 무료로 최신 업로드 메타데이터를 가져온다.
 * https://www.youtube.com/feeds/videos.xml?channel_id=UC...
 */
@Component
class YoutubeChannelClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    data class YtVideo(val videoId: String, val title: String, val url: String, val publishedAt: Instant)

    private val entryRe = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val idRe = Regex("<yt:videoId>([^<]+)</yt:videoId>")
    private val titleRe = Regex("<title>([^<]*)</title>")
    private val publishedRe = Regex("<published>([^<]+)</published>")

    /**
     * 최신 영상. titleContains 가 있으면 그 단어 포함 최신 영상을, 없으면 그냥 최신을 반환.
     * (RSS 는 최신순. Shorts 구분은 RSS 로 불가 — 제목 필터로 본방송을 노린다.)
     */
    fun latestVideo(channelId: String, titleContains: String? = null): YtVideo? {
        val xml = runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) { log.warn("YouTube RSS HTTP {} for {}", res.statusCode(), channelId); null }
            else res.body()
        }.getOrNull() ?: return null

        val videos = entryRe.findAll(xml).mapNotNull { m ->
            val e = m.groupValues[1]
            val id = idRe.find(e)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = titleRe.find(e)?.groupValues?.get(1)?.let(::unescape) ?: ""
            val published = publishedRe.find(e)?.groupValues?.get(1)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
            YtVideo(id, title, "https://www.youtube.com/watch?v=$id", published)
        }.toList()

        val filtered = if (!titleContains.isNullOrBlank()) videos.filter { it.title.contains(titleContains) } else videos
        return filtered.firstOrNull() ?: videos.firstOrNull()
    }

    private fun unescape(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
}
