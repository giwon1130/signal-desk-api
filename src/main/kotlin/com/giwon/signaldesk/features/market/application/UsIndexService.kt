package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

/**
 * 미국 지수(S&P500·NASDAQ) 단일 제공자 — 야후 v8(어젯밤 종가 반영) 우선, 실패 시 FRED 폴백.
 *
 * 배경: FRED 의 SP500/NASDAQCOM 일간 시리즈는 미국 '다음 영업일'에 발행된다. 브리프가 도는 시각
 * (이브닝 06:30·모닝 08:30·장중 15:40 KST)에는 어젯밤 미국장 종가가 아직 FRED 에 없어, 항상 한 세션
 * 늦은 값을 "현재값"으로 들고 온다. 그 결과 어젯밤 하락을 직전 세션(상승)으로 오인해 브리프가 "상승"으로
 * 잘못 표기됐다. 야후는 마감 직후 종가가 즉시 반영되므로 1순위로 두고, 야후 장애 시에만 FRED 로 폴백한다.
 *
 * 모닝/이브닝/장중 브리프 + 마켓 인사이트 + 앱 마켓 오버뷰 카드 + 마켓 히트가 모두 이 한 곳을 거친다.
 */
@Component
class UsIndexService(
    private val yahooQuoteClient: YahooQuoteClient,
    private val fredIndexClient: FredIndexClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable(cacheNames = ["macro-index"], key = "'us-indices'", unless = "#result == null")
    fun fetchUsIndices(): UsIndicesSnapshot? {
        yahooQuoteClient.fetchUsIndices()?.let { return it }
        log.warn("US indices: 야후 조회 실패 → FRED 폴백(한 세션 지연 가능성 있음)")
        return fredIndexClient.fetchUsIndices()
    }

    /** 위험도용 거시 시세(환율·미 10년물) — 라이브 야후만(전일 대비 정확). 실패 시 null. */
    @Cacheable(cacheNames = ["macro-index"], key = "'macro-quotes'", unless = "#result == null")
    fun fetchMacroQuotes(): MacroQuotesSnapshot? = yahooQuoteClient.fetchMacroQuotes()
}
