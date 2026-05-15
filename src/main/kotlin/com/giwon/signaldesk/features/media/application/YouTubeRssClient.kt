package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 유튜브 채널의 최신 영상 목록을 RSS 로 가져온다 (API 키 불필요).
 *
 * 엔드포인트: https://www.youtube.com/feeds/videos.xml?channel_id={CHANNEL_ID}
 *   → Atom 피드, 최근 15개 영상.
 */
@Component
class YouTubeRssClient(
    @Value("\${signal-desk.integrations.youtube.rss-base-url}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchLatestVideos(channelId: String): List<YouTubeVideo> {
        if (channelId.isBlank()) return emptyList()
        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl?channel_id=$channelId"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/atom+xml, application/xml;q=0.9")
                .GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                log.warn("YouTube RSS {} returned {}", channelId, response.statusCode())
                return emptyList()
            }
            parseFeed(response.body(), channelId)
        }.getOrElse {
            log.warn("YouTube RSS fetch failed for {}", channelId, it)
            emptyList()
        }
    }

    private fun parseFeed(bytes: ByteArray, channelId: String): List<YouTubeVideo> {
        val builderFactory = DocumentBuilderFactory.newInstance()
        val builder = builderFactory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(bytes))

        // 채널명: 피드 루트의 <author><name> 또는 <title>
        val channelTitle = document.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()

        val entries = document.getElementsByTagName("entry")
        return (0 until entries.length).mapNotNull { idx ->
            val entry = entries.item(idx) as? Element ?: return@mapNotNull null
            val videoId = entry.getElementsByTagName("yt:videoId").item(0)?.textContent?.trim()
                ?: return@mapNotNull null
            val title = entry.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()
            val publishedRaw = entry.getElementsByTagName("published").item(0)?.textContent?.trim().orEmpty()
            val published = runCatching { Instant.parse(publishedRaw) }.getOrNull() ?: Instant.now()
            // media:description 은 entry > media:group > media:description 안에 있음.
            // 자막 fetch 가 실패할 때 fallback 으로 사용한다.
            val description = entry.getElementsByTagName("media:description")
                .item(0)?.textContent?.trim().orEmpty()

            YouTubeVideo(
                videoId = videoId,
                channelId = channelId,
                channelTitle = channelTitle,
                title = title,
                url = "https://www.youtube.com/watch?v=$videoId",
                publishedAt = published,
                description = description,
            )
        }
    }
}
