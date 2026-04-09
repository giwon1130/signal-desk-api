package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.features.market.application.MarketOverviewResponse
import com.giwon.signaldesk.features.market.application.MarketSectionsResponse
import com.giwon.signaldesk.features.market.application.MarketSummaryResponse
import com.giwon.signaldesk.features.market.application.MarketOverviewService
import com.giwon.signaldesk.features.market.application.NewsFeedResponse
import com.giwon.signaldesk.features.market.application.PaperTradingResponse
import com.giwon.signaldesk.features.market.application.PortfolioResponse
import com.giwon.signaldesk.features.market.application.AiRecommendationsResponse
import com.giwon.signaldesk.features.market.application.WatchlistResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/market")
class MarketOverviewController(
    private val marketOverviewService: MarketOverviewService,
) {
    @GetMapping("/summary")
    fun getSummary(): ApiResponse<MarketSummaryResponse> {
        return ApiResponse(true, marketOverviewService.getSummary())
    }

    @GetMapping("/sections")
    fun getMarketSections(): ApiResponse<MarketSectionsResponse> {
        return ApiResponse(true, marketOverviewService.getMarketSections())
    }

    @GetMapping("/news")
    fun getNews(): ApiResponse<NewsFeedResponse> {
        return ApiResponse(true, marketOverviewService.getNewsFeed())
    }

    @GetMapping("/watchlist")
    fun getWatchlist(): ApiResponse<WatchlistResponse> {
        return ApiResponse(true, marketOverviewService.getWatchlist())
    }

    @GetMapping("/portfolio")
    fun getPortfolio(): ApiResponse<PortfolioResponse> {
        return ApiResponse(true, marketOverviewService.getPortfolio())
    }

    @GetMapping("/ai-recommendations")
    fun getAiRecommendations(): ApiResponse<AiRecommendationsResponse> {
        return ApiResponse(true, marketOverviewService.getAiRecommendations())
    }

    @GetMapping("/paper-trading")
    fun getPaperTrading(): ApiResponse<PaperTradingResponse> {
        return ApiResponse(true, marketOverviewService.getPaperTrading())
    }

    @GetMapping("/overview")
    fun getOverview(): ApiResponse<MarketOverviewResponse> {
        return ApiResponse(
            success = true,
            data = marketOverviewService.getOverview()
        )
    }
}

data class ApiResponse<T>(
    val success: Boolean,
    val data: T,
)
