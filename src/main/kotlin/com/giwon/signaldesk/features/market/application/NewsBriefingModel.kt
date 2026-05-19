package com.giwon.signaldesk.features.market.application

// 뉴스 + 일일 브리핑 도메인.

data class MarketNews(
    val market: String,
    val title: String,
    val source: String,
    val url: String,
    val impact: String,
    val publishedAt: String? = null,   // RSS pubDate → ISO-8601. 없으면 null.
)

data class NewsSentiment(
    val market: String,            // "KR" / "US"
    val score: Int,                // 0~100 (50=중립)
    val label: String,             // 긍정 / 중립 / 부정
    val rationale: String,
    val positiveCount: Int,
    val negativeCount: Int,
    val neutralCount: Int,
    val highlights: List<NewsHighlight>,
)

data class NewsHighlight(
    val title: String,
    val source: String,
    val url: String,
    val tone: String,              // 긍정 / 중립 / 부정
    val publishedAt: String? = null,  // ISO-8601. 없으면 null.
)

data class DailyBriefing(
    val headline: String,
    val preMarket: List<String>,
    val afterMarket: List<String>,
    val narrative: String = "",
    val slot: String = "INTRADAY",
    val context: BriefingContext? = null,
    val actionItems: List<BriefingAction> = emptyList(),
)

data class BriefingContext(
    val holdingPnlLabel: String?,
    val holdingPnlRate: Double?,
    val watchlistAlertCount: Int,
    val marketMood: String,
    val keyEvent: String?,
)

data class BriefingAction(
    val priority: String,
    val category: String,
    val title: String,
    val detail: String,
    val ticker: String?,
    val market: String?,
)
