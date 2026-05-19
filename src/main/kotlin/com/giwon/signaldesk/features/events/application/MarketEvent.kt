package com.giwon.signaldesk.features.events.application

/**
 * 시장 이벤트(FOMC/실적/경제지표/휴장 등).
 *
 * 첫 단계는 정적 큐레이션 + KR/US 휴장일 자동 노출.
 * 데이터 소스 확장(Trading Economics 등)은 사용자 검증 후 follow-up.
 */
data class MarketEvent(
    val id: String,
    val date: String,           // ISO yyyy-MM-dd (KST 기준)
    val time: String?,          // "21:00 KST" 같은 시각. 종일이면 null
    val market: String,         // "KR" | "US" | "GLOBAL"
    val category: EventCategory,
    val title: String,
    val description: String? = null,
    val importance: Importance = Importance.MEDIUM,
    val tickers: List<String> = emptyList(),
)

enum class EventCategory { FOMC, EARNINGS, POLICY, ECONOMIC_DATA, HOLIDAY, OTHER }

enum class Importance { HIGH, MEDIUM, LOW }
