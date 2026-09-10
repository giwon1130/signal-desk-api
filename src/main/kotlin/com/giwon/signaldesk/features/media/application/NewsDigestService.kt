package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
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
 * 뉴스 제목과 공통 규칙 시황을 구분해 제공한다. 제목만으로 인과관계를 생성하지 않는다.
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
    private val briefing: EvidenceBriefingService,
    private val analyzer: com.giwon.signaldesk.features.market.application.MarketEvidenceAnalyzer,
    private val repository: MediaSummaryRepository,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** market: "KR" or "US" */
    fun runDigest(market: String, force: Boolean = false): MediaSummary? {
        val today = LocalDate.now(clock)
        val videoId = "news-${today.format(dateFmt)}-$market"
        if (!force && repository.findByVideoId(videoId) != null) {
            log.info("news digest already exists. videoId={}", videoId)
            return null
        }

        val allNews = runCatching { newsRssClient.fetchMarketNews() }.getOrNull()
        if (allNews.isNullOrEmpty()) {
            log.warn("news digest skipped — no news fetched")
            return null
        }
        val marketNews = analyzer.recentNews(allNews, clock.instant()).filter { it.market == market }
        if (marketNews.isEmpty()) {
            log.warn("news digest skipped — no $market news in batch")
            return null
        }

        val headlines = marketNews.map { Triple(it.source, it.title, it.url) }
        val analysis = briefing.current()

        val marketKo = if (market == "KR") "한국 시장" else "미국 시장"
        val title = "${today.format(DateTimeFormatter.ofPattern("M월 d일"))} $marketKo 마감 종합 (${marketNews.map { it.source }.distinct().size}개 매체)"
        val summary = MediaSummary(
            id = UUID.randomUUID().toString(),
            channelId = "news-digest-$market",
            channelTitle = "시장 마감 종합 · $market",
            videoId = videoId,
            videoTitle = title,
            videoUrl = "",
            publishedAt = Instant.now(),
            transcriptLength = headlines.sumOf { it.second.length },
            summary = "최근 24시간 뉴스 제목을 모았어. 사건의 확정이나 주가 변동의 원인으로 단정한 내용은 아니야.\n\n" +
                marketNews.take(8).joinToString("\n") { "[${it.source}] ${it.title} — ${it.url}" },
            flowAnalysis = analysis.summary + "\n\n" + analysis.keyPoints.joinToString("\n"),
            keyTickers = emptyList(),
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
