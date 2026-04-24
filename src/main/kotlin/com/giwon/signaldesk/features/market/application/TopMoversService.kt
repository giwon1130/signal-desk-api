package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * 시장별 급등/급락 종목 조합 서비스.
 * KOSPI·KOSDAQ 의 up/down 을 병렬로 조회해서 한 번에 응답.
 */
@Service
class TopMoversService(
    private val topMoversClient: TopMoversClient,
) {
    fun fetchTopMovers(limit: Int = 10): TopMoversResponse {
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

        return TopMoversResponse(
            generatedAt = Instant.now().toString(),
            kospi = TopMoversBlock(gainers = kospiGainers.join(), losers = kospiLosers.join()),
            kosdaq = TopMoversBlock(gainers = kosdaqGainers.join(), losers = kosdaqLosers.join()),
        )
    }
}
