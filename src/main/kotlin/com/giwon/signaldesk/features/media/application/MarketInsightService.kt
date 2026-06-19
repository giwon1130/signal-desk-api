package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.events.application.MarketEventService
import com.giwon.signaldesk.features.market.application.CboeVixClient
import com.giwon.signaldesk.features.market.application.GoogleNewsRssClient
import com.giwon.signaldesk.features.market.application.MarketSessionService
import com.giwon.signaldesk.features.market.application.UsIndexService
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

/**
 * VIX + FRED 지수 + 뉴스 헤드라인을 Gemini로 종합해 오늘의 마켓 인사이트를 생성.
 *
 * - 캐시 TTL: 30분 (market-insight)
 * - 의존 데이터는 모두 이미 개별 캐시(macro-index, rss-feed)가 있어서 추가 API 비용 없음.
 * - Gemini 미설정이면 null 반환.
 * - 한국·미국 모두 휴장(주말 + 공휴일) 시 Gemini 호출 생략하고 안내 메시지 반환 → 비용 절감.
 */
@Service
class MarketInsightService(
    private val cboeVixClient: CboeVixClient,
    private val usIndexService: UsIndexService,
    private val newsRssClient: GoogleNewsRssClient,
    private val geminiClient: GeminiClient,
    private val marketSessionService: MarketSessionService,
    private val marketEventService: MarketEventService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // unless: null(=Gemini 일시 실패/미설정)은 캐시하지 않음 — 단발 실패가 30분간 'no insight'로 굳지 않게.
    @Cacheable("market-insight", unless = "#result == null")
    fun getTodayInsight(): MarketInsightAnalysis? {
        if (!geminiClient.isEnabled()) {
            log.info("MarketInsightService skipped — Gemini 미설정")
            return null
        }

        // 한국·미국 모두 휴장이면 Gemini 호출 생략 (불필요한 API 콜 절감)
        val krToday = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val usToday = LocalDate.now(ZoneId.of("America/New_York"))
        val krTrading = marketSessionService.isKrTradingDay(krToday)
        val usTrading = marketSessionService.isUsTradingDay(usToday)
        if (!krTrading && !usTrading) {
            log.info("MarketInsightService skipped — 한국·미국 모두 휴장")
            return MarketInsightAnalysis(
                headline = "오늘은 양 시장 모두 휴장",
                summary = "한국·미국 두 시장 모두 거래일이 아닙니다. 데이터 변동이 적은 날이라 시나리오 점검과 다음 거래일 준비에 집중하세요.",
                sentiment = MediaSentiment.NEUTRAL,
                keyPoints = listOf("거래 없음 — 진입 X", "다음 거래일 후보 정리", "손절·익절 라인 재점검"),
            )
        }

        val vix = runCatching { cboeVixClient.fetchVix() }.getOrNull()
        // stale(FRED 폴백) 지수는 한 세션 지연이라 방향 오류 위험 → 지수 빼고 작성(데이터 없음 취급).
        val indices = runCatching { usIndexService.fetchUsIndices() }.getOrNull()?.let {
            if (it.stale) {
                log.warn("MarketInsight — 미국 지수 stale(FRED 폴백) → 지수 방향 표기 생략(뉴스 중심)")
                null
            } else it
        }
        val headlines = runCatching { newsRssClient.fetchMarketNews() }.getOrNull() ?: emptyList()
        // 다가오는 3일치 이벤트 (FOMC/실적/휴장 등) — Gemini가 시점 컨텍스트로 활용
        val upcomingEvents = runCatching { marketEventService.upcoming(3) }.getOrNull() ?: emptyList()

        return runCatching {
            geminiClient.summarizeMarketInsight(vix, indices, headlines, upcomingEvents)
        }.getOrElse {
            log.warn("MarketInsightService Gemini call failed", it)
            null
        }
    }
}
