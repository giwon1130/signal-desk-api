package com.giwon.signaldesk.features.rounds.application

import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import com.giwon.signaldesk.features.market.application.StockQuote
import com.giwon.signaldesk.features.media.application.YoutubeChannelClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 반도체 변동성 라운드 자동화.
 *
 * 자동화 대상은 가격 변동, 공개 RSS 메타데이터(제목·발행시각·공식 URL)뿐이다.
 * 영상 자막/본문을 수집하거나 AI로 요약하지 않으며, 기존 운영자 큐레이션도 덮어쓰지 않는다.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class SemiconductorRoundAutomationService(
    private val rounds: MarketRoundService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
    private val youtube: YoutubeChannelClient,
    @Value("\${signal-desk.rounds.automation.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.rounds.automation.kr-chip-drop-pct:-4.0}") private val krDropThreshold: Double,
    @Value("\${signal-desk.rounds.automation.us-chip-drop-pct:-5.0}") private val usDropThreshold: Double,
    @Value("\${signal-desk.rounds.automation.youtube-channel-id:UChlv4GSd7OQl3js-jkLOnFA}") private val youtubeChannelId: String,
    @Value("\${signal-desk.rounds.automation.youtube-source-name:삼프로TV}") private val youtubeSourceName: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** KR 장중/마감 뒤 호출. 한국 대형 반도체 2종 평균 하락률로 판단한다. */
    fun runKr(): Boolean = run("KR", krQuotes.fetchKoreanQuotes(KR_TICKERS))

    /** 미국 장 마감 뒤 호출. 핵심 칩주 3종 중 2종 이상이 임계치보다 하락해야 한다. */
    fun runUs(): Boolean = run("US", usQuotes.fetchUsQuotes(US_TICKERS))

    private fun run(market: String, quotes: Map<String, StockQuote>): Boolean {
        if (!enabled) return false
        val active = rounds.active()
        val videos = relevantVideos()
        if (active != null) {
            // 다른 이벤트(FOMC·실적 등) 라운드의 운영자 큐레이션에는 반도체 영상을 섞지 않는다.
            if (!active.title.contains("반도체")) return false
            val added = rounds.appendContentsIfMissing(active.id, videos)
            if (added > 0) log.info("Market round {} appended {} official video(s)", active.id, added)
            return added > 0
        }
        if (!isDropSignal(market, quotes) || videos.isEmpty()) return false

        val today = LocalDate.now(KST)
        val now = Instant.now()
        val id = "auto-semiconductor-${today}"
        rounds.save(
            MarketRoundDraft(
                id = id,
                title = "반도체 변동성 라운드",
                summary = "반도체 급락 신호가 감지됐어요. 공포보다 원인과 다음 확인 시점을 분리해서 봐요.",
                riskLevel = "HIGH",
                marketScope = "BOTH",
                checkpoints = listOf(
                    "주요 반도체주의 동반 하락인지 확인",
                    "AI 투자·실적 가이던스 관련 새 정보 확인",
                    "국내 수급과 원·달러 흐름 확인",
                ),
                startsAt = now,
                endsAt = now.plus(Duration.ofHours(72)),
                contents = videos,
            ),
        )
        log.info("Market round {} opened automatically after {} semiconductor drop signal", id, market)
        return true
    }

    private fun relevantVideos(): List<MarketRoundContentDraft> {
        if (youtubeChannelId.isBlank()) return emptyList()
        val cutoff = Instant.now().minus(Duration.ofHours(72))
        return youtube.recentVideos(youtubeChannelId)
            .asSequence()
            .filter { it.publishedAt.isAfter(cutoff) && containsSemiconductorKeyword(it.title) }
            .take(MAX_AUTOMATED_CONTENTS)
            .map {
                MarketRoundContentDraft(
                    kind = "VIDEO", sourceName = youtubeSourceName, title = it.title, url = it.url,
                    publishedAt = it.publishedAt, whyRecommended = "반도체 변동성 신호와 관련 키워드가 확인된 공식 최신 영상이에요.",
                    label = "공식 시장 해설", official = true,
                )
            }.toList()
    }

    internal fun isDropSignal(market: String, quotes: Map<String, StockQuote>): Boolean {
        val values = quotes.values.map(StockQuote::changeRate)
        if (market == "KR") return values.size == KR_TICKERS.size && values.average() <= krDropThreshold
        return values.count { it <= usDropThreshold } >= 2
    }

    internal fun containsSemiconductorKeyword(title: String): Boolean = KEYWORDS.any { title.contains(it, ignoreCase = true) }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val KR_TICKERS = listOf("005930", "000660")
        val US_TICKERS = listOf("NVDA", "AMD", "MU")
        const val MAX_AUTOMATED_CONTENTS = 2
        val KEYWORDS = listOf("반도체", "AI", "메모리", "삼성전자", "하이닉스", "엔비디아", "nvidia", "칩")
    }
}
