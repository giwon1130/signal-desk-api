package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Naver 금융 "해외주식" 조회 API 로 미국 주식 단일 티커 현재가를 가져온다.
 *
 * 공식 문서는 없지만 naver stock 모바일 앱이 쓰는 엔드포인트를 사용:
 *   GET https://api.stock.naver.com/stock/{TICKER}.O/basic   (NASDAQ/OTC 계열)
 *   GET https://api.stock.naver.com/stock/{TICKER}.K/basic   (NYSE 계열이 실제로는 .K 가 아님 — 주로 .O 로도 커버됨)
 *
 * 실전적으로 대부분의 주요 미국 종목은 `.O` suffix 로 응답이 온다.
 * 응답 예:
 *   { "closePrice": "204.15", "compareToPreviousClosePrice": "0.81", "fluctuationsRatio": "0.40", ... }
 *
 * 여러 티커를 한 번에 조회하는 엔드포인트는 없어서 병렬(CompletableFuture)로 호출.
 */
@Component
class NaverGlobalQuoteClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.naver-global.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.naver-global.base-url:https://api.stock.naver.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchUsQuotes(tickers: Collection<String>): Map<String, StockQuote> {
        if (!enabled || tickers.isEmpty()) return emptyMap()

        val futures = tickers.distinct().map { ticker ->
            CompletableFuture.supplyAsync {
                runCatching { fetchOne(ticker) }
                    .onFailure { log.debug("Naver global quote failed. ticker={}, err={}", ticker, it.message) }
                    .getOrNull()
            }
        }
        return futures
            .mapNotNull { it.join() }
            .associateBy { it.ticker }
    }

    private fun fetchOne(ticker: String): StockQuote? {
        // NASDAQ/OTC 가 대부분이고 실패하면 .K 로 한 번 더 시도.
        return fetchSuffixed(ticker, "O") ?: fetchSuffixed(ticker, "K")
    }

    private fun fetchSuffixed(ticker: String, suffix: String): StockQuote? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/stock/$ticker.$suffix/basic"))
            .timeout(Duration.ofSeconds(4))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .header("Referer", "https://m.stock.naver.com/")
            .GET()
            .build()

        val response = runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (response.statusCode() !in 200..299) return null

        val root = runCatching { objectMapper.readTree(response.body()) }.getOrNull() ?: return null
        val close = root["closePrice"]?.asText()?.replace(",", "")?.toDoubleOrNull() ?: return null
        val rate = root["fluctuationsRatio"]?.asText()?.replace(",", "")?.toDoubleOrNull() ?: 0.0

        // Int 변환 시 소수점 손실을 피하려면 Naver KR 과 일관되게 Int 로 반올림.
        return StockQuote(
            ticker = ticker,
            currentPrice = close.toInt(),
            changeRate = rate,
        )
    }
}
