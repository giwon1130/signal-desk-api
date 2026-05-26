package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

/**
 * 시장별 급등/급락 종목 조합 서비스.
 * KR(KOSPI·KOSDAQ) + US(NASDAQ/NYSE 통합) up/down 을 병렬 조회해 한 번에 응답.
 */
@Service
class TopMoversService(
    private val topMoversClient: TopMoversClient,
    private val yahooFinanceScreenerClient: YahooFinanceScreenerClient,
) {
    fun fetchTopMovers(limit: Int = 10): TopMoversResponse {
        // KR — Naver 시세 페이지 scraping
        val kospiGainers = CompletableFuture.supplyAsync {
            topMoversClient.fetchTopMovers(TopMoversClient.KoreanMarket.KOSPI, TopMoversClient.Direction.GAINERS, limit)
        }
        val kospiLosers = CompletableFuture.supplyAsync {
            topMoversClient.fetchTopMovers(TopMoversClient.KoreanMarket.KOSPI, TopMoversClient.Direction.LOSERS, limit)
        }
        val kosdaqGainers = CompletableFuture.supplyAsync {
            topMoversClient.fetchTopMovers(TopMoversClient.KoreanMarket.KOSDAQ, TopMoversClient.Direction.GAINERS, limit)
        }
        val kosdaqLosers = CompletableFuture.supplyAsync {
            topMoversClient.fetchTopMovers(TopMoversClient.KoreanMarket.KOSDAQ, TopMoversClient.Direction.LOSERS, limit)
        }
        // US — Yahoo Finance screener
        val usGainers = CompletableFuture.supplyAsync { yahooFinanceScreenerClient.fetchGainers(limit) }
        val usLosers = CompletableFuture.supplyAsync { yahooFinanceScreenerClient.fetchLosers(limit) }

        return TopMoversResponse(
            generatedAt = Instant.now().toString(),
            kospi = TopMoversBlock(gainers = kospiGainers.join(), losers = kospiLosers.join()),
            kosdaq = TopMoversBlock(gainers = kosdaqGainers.join(), losers = kosdaqLosers.join()),
            us = TopMoversBlock(
                gainers = usGainers.join().map { it.toTopMover() },
                losers = usLosers.join().map { it.toTopMover() },
            ),
        )
    }

    private fun YahooQuote.toTopMover(): TopMover = TopMover(
        market = "US",
        ticker = ticker,
        name = name,
        price = price.roundToInt(),
        changeRate = changeRate,
    )
}
