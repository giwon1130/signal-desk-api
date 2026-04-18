package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.application.MarketOverviewResponse
import com.giwon.signaldesk.features.market.application.MarketSectionsResponse
import com.giwon.signaldesk.features.market.application.MarketSummaryResponse
import com.giwon.signaldesk.features.market.application.MarketOverviewService
import com.giwon.signaldesk.features.market.application.NewsFeedResponse
import com.giwon.signaldesk.features.market.application.PaperTradingResponse
import com.giwon.signaldesk.features.market.application.PortfolioResponse
import com.giwon.signaldesk.features.market.application.AiRecommendationsResponse
import com.giwon.signaldesk.features.market.application.WatchlistResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/market")
class MarketOverviewController(
    private val marketOverviewService: MarketOverviewService,
    @Autowired(required = false) private val authContext: AuthContext? = null,
) {
    private fun userId(auth: String?) = authContext?.optionalUserId(auth)

    @GetMapping("/summary")
    fun getSummary(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<MarketSummaryResponse> {
        return ApiResponse(true, marketOverviewService.getSummary(userId(auth)))
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
    fun getWatchlist(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<WatchlistResponse> {
        return ApiResponse(true, marketOverviewService.getWatchlist(userId(auth)))
    }

    @GetMapping("/portfolio")
    fun getPortfolio(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<PortfolioResponse> {
        return ApiResponse(true, marketOverviewService.getPortfolio(userId(auth)))
    }

    @GetMapping("/ai-recommendations")
    fun getAiRecommendations(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<AiRecommendationsResponse> {
        return ApiResponse(true, marketOverviewService.getAiRecommendations(userId(auth)))
    }

    @GetMapping("/paper-trading")
    fun getPaperTrading(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<PaperTradingResponse> {
        return ApiResponse(true, marketOverviewService.getPaperTrading(userId(auth)))
    }

    @GetMapping("/overview")
    fun getOverview(@RequestHeader("Authorization", required = false) auth: String?): ApiResponse<MarketOverviewResponse> {
        return ApiResponse(
            success = true,
            data = marketOverviewService.getOverview(userId(auth))
        )
    }
}

data class ApiResponse<T>(
    val success: Boolean,
    val data: T,
)
