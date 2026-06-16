package com.giwon.signaldesk.features.market.application

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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * Yahoo Finance v8 chart API 로 임의 심볼(글로벌 지수·선물)의 현재가/등락률을 가져온다.
 *
 *   GET https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=5d&interval=1d
 *   응답: chart.result[0].meta.{regularMarketPrice, previousClose|chartPreviousClose}
 *
 * 닛케이(^N225)·항셍(^HSI)·S&P 선물(ES=F) 등 야간/아시아 방향성 지표용.
 * 비공식 endpoint라 fail-soft (빈 결과).
 */
@Component
class YahooQuoteClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.yahoo-quote.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.yahoo-quote.base-url:https://query1.finance.yahoo.com}") private val baseUrl: String,
    @org.springframework.beans.factory.annotation.Qualifier("httpFetchExecutor") private val httpFetchExecutor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    /** symbolToLabel: Yahoo 심볼 → 표시 라벨. 병렬 조회, 성공한 항목만 입력 순서대로 반환. */
    @org.springframework.cache.annotation.Cacheable(
        cacheNames = ["macro-index"],
        key = "'yq:' + new java.util.TreeSet(#symbolToLabel.keySet()).toString()",
        unless = "#result.isEmpty()",
    )
    fun fetchIndices(symbolToLabel: Map<String, String>): List<GlobalIndex> {
        if (!enabled || symbolToLabel.isEmpty()) return emptyList()
        val futures = symbolToLabel.entries.map { (symbol, label) ->
            CompletableFuture.supplyAsync({
                runCatching { fetchOne(symbol, label) }
                    .onFailure { log.debug("Yahoo quote failed. symbol={}, err={}", symbol, it.message) }
                    .getOrNull()
            }, httpFetchExecutor)
        }
        return futures.mapNotNull { it.join() }
    }

    private fun fetchOne(symbol: String, label: String): GlobalIndex? {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v8/finance/chart/$encoded?range=5d&interval=1d"))
            .timeout(Duration.ofSeconds(5))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .GET().build()
        val resp = runCatching { httpClient.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (resp.statusCode() !in 200..299) return null
        val result = runCatching { objectMapper.readTree(resp.body()) }.getOrNull()
            ?.get("chart")?.get("result")?.get(0) ?: return null
        val meta = result["meta"] ?: return null
        val price = meta["regularMarketPrice"]?.asDouble() ?: return null
        // 일간 등락률은 close 배열의 직전 종가로. 지수·선물은 meta.previousClose 가 null 이고,
        // chartPreviousClose 는 range(5d) 시작 전 종가라 '일간'이 아닌 수일치 변동이 돼버린다(닛케이/항셍/선물 과대표기).
        val closes = closesOf(result)
        val prevClose = priorClose(closes, price) ?: (meta["previousClose"] ?: meta["chartPreviousClose"])?.asDouble()
        val changeRate = if (prevClose != null && prevClose != 0.0) (price - prevClose) / prevClose * 100 else 0.0
        return GlobalIndex(label = label, value = price, changeRate = changeRate)
    }

    /** chart.result[0].indicators.quote[0].close 의 non-null 종가 배열(시간순). */
    private fun closesOf(result: com.fasterxml.jackson.databind.JsonNode): List<Double> =
        result["indicators"]?.get("quote")?.get(0)?.get("close")
            ?.mapNotNull { node -> if (node.isNull) null else node.asDouble() }
            .orEmpty()

    /** 현재가 기준 '직전 일간 종가'. 마감 후엔 마지막 close==price → 그 직전, 장중이면 마지막 close. 부족하면 null.
     *  visibility=internal: 회귀 테스트(YahooQuoteClientTest)에서 직접 검증하기 위함. */
    internal fun priorClose(closes: List<Double>, price: Double): Double? = when {
        closes.isEmpty() -> null
        kotlin.math.abs(closes.last() - price) < price * 1e-6 -> closes.getOrNull(closes.size - 2)
        else -> closes.last()
    }

    /**
     * 미국 지수(S&P500=^GSPC, NASDAQ=^IXIC) 를 야후 v8 chart 로 가져온다.
     *
     * FRED 의 SP500/NASDAQCOM 시리즈는 미국 다음 영업일에 발행돼, 브리프 시각(06:30/08:30/15:40 KST)엔
     * 어젯밤 종가가 아직 없어 한 세션 늦은 값을 준다(= 어젯밤 하락을 직전 상승으로 오인). 그래서 야후를 1순위로 쓴다.
     *
     * 일간 등락률은 close 배열의 마지막 두 종가로 계산한다 — 지수는 meta.previousClose 가 null 일 수 있고,
     * chartPreviousClose 는 range 시작 직전(=약 한 달 전) 종가라 '일간' 변화가 아니다.
     * 둘 중 하나라도 실패하면 null → 상위에서 FRED 폴백.
     */
    fun fetchUsIndices(): UsIndicesSnapshot? {
        if (!enabled) return null
        val sp500 = fetchIndexSeries("^GSPC") ?: return null
        val nasdaq = fetchIndexSeries("^IXIC") ?: return null
        return UsIndicesSnapshot(sp500 = sp500, nasdaq = nasdaq)
    }

    /**
     * 위험도용 거시 시세 — 원/달러 환율(KRW=X) + 미 10년물 금리(^TNX). 라이브(전일 대비 정확).
     * FRED 는 다음 영업일 발행이라 stale → 위험 신호엔 야후만 사용(실패 시 null → 컴포넌트 중립).
     */
    fun fetchMacroQuotes(): MacroQuotesSnapshot? {
        if (!enabled) return null
        val usdKrw = fetchIndexSeries("KRW=X")
        val us10y = fetchIndexSeries("^TNX")
        if (usdKrw == null && us10y == null) return null
        return MacroQuotesSnapshot(usdKrw = usdKrw, us10y = us10y)
    }

    /**
     * 시즈널리티 백테스트용 장기 일봉 — 배당·분할 조정 종가(adjclose). range 예: "15y".
     * adjclose 없으면 raw close 폴백. 날짜는 거래소 gmtoffset 반영한 현지 거래일. 실패 시 빈 리스트.
     */
    // sync=true 는 unless 와 병행 불가(Spring 제약) — 빈 결과(일시 실패) 비캐시가 우선.
    @org.springframework.cache.annotation.Cacheable(
        cacheNames = ["yahoo-history"],
        key = "#symbol + ':' + #range",
        unless = "#result.isEmpty()",
    )
    fun fetchDailyHistory(symbol: String, range: String = "15y"): List<HistoryBar> {
        if (!enabled) return emptyList()
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v8/finance/chart/$encoded?range=$range&interval=1d&events=div%2Csplit&includeAdjustedClose=true"))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .GET().build()
        val resp = runCatching { httpClient.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return emptyList()
        if (resp.statusCode() !in 200..299) return emptyList()
        val result = runCatching { objectMapper.readTree(resp.body()) }.getOrNull()
            ?.get("chart")?.get("result")?.get(0) ?: return emptyList()
        val timestamps = result["timestamp"] ?: return emptyList()
        val gmtoffset = result["meta"]?.get("gmtoffset")?.asLong() ?: 0L
        val indicators = result["indicators"]
        val adj = indicators?.get("adjclose")?.get(0)?.get("adjclose")
            ?: indicators?.get("quote")?.get(0)?.get("close")
            ?: return emptyList()
        val out = ArrayList<HistoryBar>(timestamps.size())
        for (i in 0 until timestamps.size()) {
            val ts = timestamps.get(i)?.asLong() ?: continue
            val node = adj.get(i) ?: continue
            if (node.isNull) continue
            val c = node.asDouble()
            if (c <= 0.0) continue
            val date = Instant.ofEpochSecond(ts + gmtoffset).atZone(ZoneOffset.UTC).toLocalDate()
            out.add(HistoryBar(date, c))
        }
        return out
    }

    private fun fetchIndexSeries(symbol: String): FredSeriesSnapshot? {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v8/finance/chart/$encoded?range=1mo&interval=1d"))
            .timeout(Duration.ofSeconds(5))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .GET().build()
        val resp = runCatching { httpClient.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (resp.statusCode() !in 200..299) return null
        val result = runCatching { objectMapper.readTree(resp.body()) }.getOrNull()
            ?.get("chart")?.get("result")?.get(0) ?: return null
        val price = result["meta"]?.get("regularMarketPrice")?.asDouble() ?: return null
        val closes = closesOf(result)
        val prev = priorClose(closes, price) ?: return null
        val changeRate = if (prev != 0.0) (price - prev) / prev * 100 else 0.0
        return FredSeriesSnapshot(currentValue = price, changeRate = changeRate, chart = closes.takeLast(20))
    }

    companion object {
        /** 브리프 야간 방향성용 — 닛케이·항셍·S&P500 선물. */
        val GLOBAL_INDICES = linkedMapOf(
            "^N225" to "닛케이225",
            "^HSI" to "항셍",
            "ES=F" to "S&P500 선물",
        )
    }
}

data class GlobalIndex(
    val label: String,
    val value: Double,
    val changeRate: Double,
)

/** 시즈널리티 백테스트용 일봉(배당·분할 조정 종가). */
data class HistoryBar(val date: LocalDate, val adjClose: Double)

/** 위험도용 거시 시세 묶음 — 원/달러 환율 + 미 10년물 금리(둘 다 야후 라이브). */
data class MacroQuotesSnapshot(
    val usdKrw: FredSeriesSnapshot?,   // 원/달러 환율 (KRW=X), currentValue=원, changeRate=전일 대비 %
    val us10y: FredSeriesSnapshot?,    // 미 10년물 금리 (^TNX), currentValue=% 수익률, changeRate=전일 대비 %
)
