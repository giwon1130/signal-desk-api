package com.giwon.signaldesk.features.market.application

// 시장 데이터 — 지수/차트/투자자 수급/리딩 종목/지표 카드
// 같은 패키지로 분리 (외부 import 영향 없음).

data class MarketSection(
    val market: String,
    val title: String,
    val indices: List<IndexMetric>,
    val sentiment: List<SentimentMetric>,
    val investorFlows: List<InvestorFlow>,
    val leadingStocks: List<TickerSnapshot>,
)

data class IndexMetric(
    val label: String,
    val value: Double,
    val changeRate: Double,
    val periods: List<ChartPeriodSnapshot>,
)

data class ChartPeriodSnapshot(
    val key: String,
    val label: String,
    val points: List<ChartPoint>,
    val stats: ChartStats,
)

data class ChartPoint(
    val label: String,
    val value: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

data class ChartStats(
    val latest: Double,
    val high: Double,
    val low: Double,
    val changeRate: Double,
    val range: Double,
    val averageVolume: Long,
)

data class SentimentMetric(
    val label: String,
    val state: String,
    val score: Int,
    val note: String,
)

data class InvestorFlow(
    val investor: String,
    val amountBillionWon: Double,
    val note: String,
    val positive: Boolean,
)

data class TickerSnapshot(
    val ticker: String,
    val name: String,
    val sector: String,
    val price: Int,
    val changeRate: Double,
    val stance: String,
)

data class SummaryMetric(
    val label: String,
    val score: Double,
    val state: String,
    val note: String,
)

data class AlternativeSignal(
    val label: String,
    val score: Int,
    val state: String,
    val note: String,
    val highlights: List<String>,
    val source: String,
    val url: String,
    val experimental: Boolean,
    val description: String = "",   // 지표가 무엇인지/어떤 데이터 기반인지 설명 (모달용)
    val methodology: String = "",   // 점수를 어떻게 계산하는지
    val personalImpact: String? = null,  // 내 관심/보유 종목과 연결된 한 줄 해석
)

data class WatchAlert(
    val severity: String,
    val category: String,
    val market: String,
    val ticker: String,
    val name: String,
    val title: String,
    val note: String,
    val score: Int,
    val tags: List<String>,
)

data class SourceNote(
    val label: String,
    val source: String,
    val url: String,
)
