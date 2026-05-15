package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.MarketNews
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 매일 장 마감 후 한국 시장 뉴스를 종합해 AI 요약을 만든다.
 *
 * 입력: GoogleNewsRssClient.fetchMarketNews() 의 KR 뉴스 헤드라인 묶음
 * 출력: MediaSummary (source=NEWS_DIGEST) — 기존 YouTube 요약과 같은 테이블/엔드포인트 공유
 *
 * 같은 날짜 + 같은 시장은 하나의 row 로 수렴 (video_id = "news-YYYY-MM-DD-KR" upsert).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class NewsDigestService(
    private val newsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
    private val repository: MediaSummaryRepository,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** market: "KR" or "US" */
    fun runDigest(market: String, force: Boolean = false): MediaSummary? {
        if (!geminiClient.isEnabled()) {
            log.warn("NewsDigestService skipped — GEMINI_API_KEY 미설정")
            return null
        }

        val today = LocalDate.now(clock)
        val videoId = "news-${today.format(dateFmt)}-$market"
        if (!force && repository.findByVideoId(videoId) != null) {
            log.info("news digest already exists. videoId={}", videoId)
            return null
        }

        val allNews = newsRssClient.fetchMarketNews()
        if (allNews.isNullOrEmpty()) {
            log.warn("news digest skipped — no news fetched")
            return null
        }
        val marketNews = allNews.filter { it.market == market }
        if (marketNews.isEmpty()) {
            log.warn("news digest skipped — no $market news in batch")
            return null
        }

        val headlines = marketNews.map { Triple(it.source, it.title, it.url) }
        val analysis = geminiClient.summarizeNewsDigest(market, today.format(dateFmt), headlines) ?: run {
            log.warn("Gemini analysis returned null. market={}", market)
            return null
        }

        val marketKo = if (market == "KR") "한국 시장" else "미국 시장"
        val title = "${today.format(DateTimeFormatter.ofPattern("M월 d일"))} $marketKo 마감 종합 (${marketNews.size}개 매체)"
        val summary = MediaSummary(
            id = UUID.randomUUID().toString(),
            channelId = "news-digest-$market",
            channelTitle = "시장 마감 종합 · $market",
            videoId = videoId,
            videoTitle = title,
            videoUrl = "",
            publishedAt = Instant.now(),
            transcriptLength = headlines.sumOf { it.second.length },
            summary = analysis.summary,
            flowAnalysis = analysis.flowAnalysis,
            keyTickers = analysis.keyTickers,
            sentiment = analysis.sentiment,
            hasTranscript = true,
            source = MediaSource.NEWS_DIGEST,
            createdAt = Instant.now(),
        )
        return repository.save(summary).also {
            log.info("news digest saved. videoId={}, market={}, newsCount={}",
                videoId, market, marketNews.size)
        }
    }

    /** 양 시장 동시 실행. 처리된 개수 반환. */
    fun runAll(force: Boolean = false): Int {
        var n = 0
        listOf("KR", "US").forEach { market ->
            runCatching { if (runDigest(market, force) != null) n++ }
                .onFailure { log.error("news digest failed market={}", market, it) }
        }
        return n
    }
}
