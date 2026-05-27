package com.giwon.signaldesk.features.events.application

/**
 * 수동 큐레이션 정적 이벤트 — 매 분기 갱신.
 *
 * 갱신 가이드:
 *  - FOMC: https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm
 *  - CPI 발표일: https://www.bls.gov/schedule/news_release/cpi.htm
 *  - PCE 발표일 (Personal Income & Outlays): https://www.bea.gov/news/schedule
 *  - 한국: https://ecos.bok.or.kr / https://open.krx.co.kr
 *
 * 시각은 KST 기준. FOMC 성명 발표는 14:00 ET (DST 적용 시 익일 03:00 KST,
 * 11월 첫째 일요일 DST 해제 후 익일 04:00 KST). CPI/PCE 는 08:30 ET
 * (DST 적용 시 21:30 KST, 해제 후 22:30 KST) 발표.
 *
 * 만료된 이벤트는 사용처(MarketEventService)에서 날짜 필터로 자동 제외되므로 굳이 지울 필요 없음.
 */
internal object MarketStaticEvents {

    val ALL: List<MarketEvent> = listOf(
        // ─── FOMC 2026 (Fed 공식 일정) ─────────────────────────────────────
        MarketEvent(
            id = "fomc-2026-06", date = "2026-06-18", time = "03:00 KST",
            market = "US", category = EventCategory.FOMC,
            title = "FOMC 성명 발표 (6월)", description = "연방공개시장위원회 정책금리 결정",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "fomc-2026-07", date = "2026-07-30", time = "03:00 KST",
            market = "US", category = EventCategory.FOMC,
            title = "FOMC 성명 발표 (7월)", description = "연방공개시장위원회 정책금리 결정",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "fomc-2026-09", date = "2026-09-17", time = "03:00 KST",
            market = "US", category = EventCategory.FOMC,
            title = "FOMC 성명 발표 (9월)", description = "정책금리 결정 + SEP 점도표 공개",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "fomc-2026-10", date = "2026-10-29", time = "03:00 KST",
            market = "US", category = EventCategory.FOMC,
            title = "FOMC 성명 발표 (10월)", description = "연방공개시장위원회 정책금리 결정",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "fomc-2026-12", date = "2026-12-17", time = "04:00 KST",
            market = "US", category = EventCategory.FOMC,
            title = "FOMC 성명 발표 (12월)", description = "정책금리 결정 + SEP 점도표 공개",
            importance = Importance.HIGH,
        ),

        // ─── CPI 2026 — BLS 발표일 (월 평균 13일 부근, 21:30~22:30 KST) ─────
        MarketEvent(
            id = "cpi-2026-05", date = "2026-06-11", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 5월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "cpi-2026-06", date = "2026-07-15", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 6월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "cpi-2026-07", date = "2026-08-12", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 7월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "cpi-2026-08", date = "2026-09-10", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 8월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "cpi-2026-09", date = "2026-10-15", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 9월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "cpi-2026-10", date = "2026-11-12", time = "22:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 10월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "cpi-2026-11", date = "2026-12-10", time = "22:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 11월 CPI 발표", description = "헤드라인·코어 소비자물가지수",
            importance = Importance.HIGH,
        ),

        // ─── PCE 2026 — BEA Personal Income & Outlays (월 말 발표) ──────────
        MarketEvent(
            id = "pce-2026-04", date = "2026-05-29", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 4월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "pce-2026-05", date = "2026-06-26", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 5월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "pce-2026-06", date = "2026-07-31", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 6월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "pce-2026-07", date = "2026-08-28", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 7월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "pce-2026-08", date = "2026-09-25", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 8월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "pce-2026-09", date = "2026-10-30", time = "21:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 9월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
            importance = Importance.HIGH,
        ),
        MarketEvent(
            id = "pce-2026-10", date = "2026-11-25", time = "22:30 KST",
            market = "US", category = EventCategory.ECONOMIC_DATA,
            title = "미국 10월 PCE 물가지수", description = "Fed 가 가장 중시하는 인플레 지표 (코어 PCE)",
            importance = Importance.HIGH,
        ),

        // ─── Jackson Hole 심포지엄 (매년 8월 말, Fed 의장 연설 주목) ────────
        MarketEvent(
            id = "jackson-hole-2026", date = "2026-08-20", time = null,
            market = "US", category = EventCategory.POLICY,
            title = "잭슨홀 경제정책 심포지엄 시작", description = "캔자스시티 연은 주최 — 의장 연설 등 통화정책 시그널 주목",
            importance = Importance.HIGH,
        ),
    )
}
