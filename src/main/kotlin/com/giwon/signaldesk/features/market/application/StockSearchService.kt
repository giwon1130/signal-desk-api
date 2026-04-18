package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

data class StockSearchResult(
    val ticker: String,
    val name: String,
    val market: String,
    val sector: String,
    val price: Int,
    val changeRate: Double,
    val stance: String,
)

@Service
class StockSearchService(
    private val naverClient: NaverFinanceQuoteClient,
    private val naverSearchClient: NaverStockSearchClient,
) {

    // ── Static Registry ─────────────────────────────────────────────────────

    private val krRegistry: List<StockSearchResult> = listOf(
        StockSearchResult("005930", "삼성전자", "KR", "반도체", 84200, 0.0, "대형 우량주 기준선"),
        StockSearchResult("000660", "SK하이닉스", "KR", "반도체", 201500, 0.0, "HBM 수혜 모멘텀"),
        StockSearchResult("035420", "NAVER", "KR", "플랫폼", 184300, 0.0, "AI 전환 주시"),
        StockSearchResult("035720", "카카오", "KR", "플랫폼", 44500, 0.0, "구조 개선 진행 중"),
        StockSearchResult("068270", "셀트리온", "KR", "바이오", 176200, 0.0, "글로벌 바이오시밀러"),
        StockSearchResult("005380", "현대차", "KR", "자동차", 248500, 0.0, "EV 전환 추세"),
        StockSearchResult("000270", "기아", "KR", "자동차", 107500, 0.0, "SUV·EV 실적 안정"),
        StockSearchResult("105560", "KB금융", "KR", "금융", 78100, 0.0, "배당 안정성 우수"),
        StockSearchResult("055550", "신한지주", "KR", "금융", 50900, 0.0, "밸류업 수혜"),
        StockSearchResult("086790", "하나금융지주", "KR", "금융", 69800, 0.0, "배당 확대 정책"),
        StockSearchResult("003550", "LG", "KR", "지주", 100500, 0.0, "지주 할인 해소 기대"),
        StockSearchResult("066570", "LG전자", "KR", "전자", 107800, 0.0, "구조 변화 시작"),
        StockSearchResult("096770", "SK이노베이션", "KR", "에너지/배터리", 131200, 0.0, "배터리 분사 이후"),
        StockSearchResult("006400", "삼성SDI", "KR", "배터리", 310000, 0.0, "프리미엄 배터리"),
        StockSearchResult("051910", "LG화학", "KR", "화학/배터리", 290000, 0.0, "배터리 분사 구조"),
        StockSearchResult("009830", "한화솔루션", "KR", "태양광", 31000, 0.0, "미국 IRA 수혜"),
        StockSearchResult("028260", "삼성물산", "KR", "건설/지주", 165200, 0.0, "지주 연관 밸류업"),
        StockSearchResult("207940", "삼성바이오로직스", "KR", "바이오", 900000, 0.0, "CMO 글로벌 선두"),
        StockSearchResult("373220", "LG에너지솔루션", "KR", "배터리", 370000, 0.0, "전기차 배터리 핵심"),
        StockSearchResult("247540", "에코프로비엠", "KR", "이차전지소재", 131500, 0.0, "양극재 선도"),
        StockSearchResult("011200", "HMM", "KR", "해운", 15200, 0.0, "운임 사이클 주시"),
        StockSearchResult("032830", "삼성생명", "KR", "보험", 101000, 0.0, "IFRS17 전환 수혜"),
        StockSearchResult("030200", "KT", "KR", "통신", 40000, 0.0, "배당 안정, AI 전환"),
        StockSearchResult("017670", "SK텔레콤", "KR", "통신", 54200, 0.0, "AI 서비스 확장"),
        StockSearchResult("018260", "삼성에스디에스", "KR", "IT서비스", 160000, 0.0, "B2B AI 전환"),
        StockSearchResult("034730", "SK", "KR", "지주", 158000, 0.0, "에너지·반도체 지주"),
        StockSearchResult("004020", "현대제철", "KR", "철강", 30900, 0.0, "경기 민감 대형 철강"),
        StockSearchResult("000810", "삼성화재", "KR", "보험", 311000, 0.0, "IFRS17 수혜 선두"),
        StockSearchResult("267250", "HD현대중공업", "KR", "조선", 214500, 0.0, "LNG선 수주 모멘텀"),
        StockSearchResult("329180", "HD현대", "KR", "조선/지주", 80400, 0.0, "조선·에너지 그룹 지주"),
    )

    private val usRegistry: List<StockSearchResult> = listOf(
        StockSearchResult("NVDA", "NVIDIA", "US", "AI 반도체", 945, 0.0, "AI 인프라 핵심"),
        StockSearchResult("MSFT", "Microsoft", "US", "플랫폼/클라우드", 428, 0.0, "Azure·Copilot 성장"),
        StockSearchResult("AAPL", "Apple", "US", "하드웨어/생태계", 188, 0.0, "AI폰 전환 기대"),
        StockSearchResult("AMZN", "Amazon", "US", "커머스/클라우드", 184, 0.0, "AWS 성장 가속"),
        StockSearchResult("GOOGL", "Alphabet", "US", "검색/광고/AI", 164, 0.0, "Gemini 모멘텀"),
        StockSearchResult("META", "Meta Platforms", "US", "광고/플랫폼", 503, 0.0, "AI 광고 수익화"),
        StockSearchResult("TSLA", "Tesla", "US", "전기차/에너지", 171, 0.0, "로보택시 기대"),
        StockSearchResult("BRK.B", "Berkshire Hathaway B", "US", "금융/지주", 396, 0.0, "방어적 가치주"),
        StockSearchResult("JPM", "JPMorgan Chase", "US", "금융", 200, 0.0, "금리 사이클 수혜"),
        StockSearchResult("V", "Visa", "US", "결제", 275, 0.0, "글로벌 결제 네트워크"),
        StockSearchResult("MA", "Mastercard", "US", "결제", 461, 0.0, "국제 거래 성장"),
        StockSearchResult("LLY", "Eli Lilly", "US", "제약/비만약", 760, 0.0, "GLP-1 성장 가속"),
        StockSearchResult("JNJ", "Johnson & Johnson", "US", "헬스케어", 155, 0.0, "방어주 배당"),
        StockSearchResult("UNH", "UnitedHealth", "US", "헬스케어", 530, 0.0, "의료보험 선두"),
        StockSearchResult("XOM", "ExxonMobil", "US", "에너지", 108, 0.0, "배당 안정 에너지"),
        StockSearchResult("CVX", "Chevron", "US", "에너지", 153, 0.0, "통합 에너지 배당"),
        StockSearchResult("PG", "Procter & Gamble", "US", "소비재", 165, 0.0, "필수소비재 방어"),
        StockSearchResult("KO", "Coca-Cola", "US", "음료", 62, 0.0, "배당왕 방어주"),
        StockSearchResult("AMD", "Advanced Micro Devices", "US", "반도체", 155, 0.0, "MI 시리즈 AI 경쟁"),
        StockSearchResult("AVGO", "Broadcom", "US", "반도체/네트워크", 170, 0.0, "ASIC·네트워크 칩"),
        StockSearchResult("CRM", "Salesforce", "US", "SaaS/CRM", 278, 0.0, "AI 에이전트 전환"),
        StockSearchResult("NOW", "ServiceNow", "US", "SaaS", 790, 0.0, "엔터프라이즈 AI"),
        StockSearchResult("PLTR", "Palantir", "US", "AI 플랫폼", 23, 0.0, "방산·정부 AI 계약"),
        StockSearchResult("CRWD", "CrowdStrike", "US", "사이버보안", 360, 0.0, "엔드포인트 선두"),
        StockSearchResult("PANW", "Palo Alto Networks", "US", "사이버보안", 315, 0.0, "플랫폼화 전략"),
        StockSearchResult("NFLX", "Netflix", "US", "스트리밍", 630, 0.0, "광고 티어 성장"),
        StockSearchResult("SPOT", "Spotify", "US", "오디오 스트리밍", 330, 0.0, "팟캐스트·AI 추천"),
        StockSearchResult("COIN", "Coinbase", "US", "암호화폐 거래소", 230, 0.0, "비트코인 사이클 연동"),
        StockSearchResult("HOOD", "Robinhood", "US", "핀테크/브로커리지", 21, 0.0, "리테일 투자 플랫폼"),
        StockSearchResult("SBUX", "Starbucks", "US", "식음료", 79, 0.0, "글로벌 브랜드 회복"),
    )

    // ── Search ──────────────────────────────────────────────────────────────

    fun search(query: String, market: String?, limit: Int): List<StockSearchResult> {
        val keyword = query.trim().lowercase()
        val pool = when (market?.uppercase()) {
            "KR" -> krRegistry
            "US" -> usRegistry
            else -> krRegistry + usRegistry
        }

        val staticMatched = if (keyword.isBlank()) {
            pool
        } else {
            pool.filter { item ->
                item.ticker.lowercase().contains(keyword)
                    || item.name.lowercase().contains(keyword)
                    || item.sector.lowercase().contains(keyword)
            }
        }

        // 정적 결과가 부족하면 Naver 동적 검색으로 보강
        val dynamicMatched = if (keyword.isNotBlank() && staticMatched.size < limit) {
            val seen = staticMatched.map { "${it.market}:${it.ticker}" }.toMutableSet()
            naverSearchClient.search(keyword, limit).filter { extra ->
                val key = "${extra.market}:${extra.ticker}"
                val accepted = market.isNullOrBlank() || market.equals("ALL", true) ||
                               extra.market.equals(market, true)
                accepted && seen.add(key)
            }
        } else emptyList()

        val results = (staticMatched + dynamicMatched).take(limit)

        // Enrich KR results with real-time prices
        val krTickers = results.filter { it.market == "KR" }.map { it.ticker }
        val quotes = if (krTickers.isNotEmpty()) naverClient.fetchKoreanQuotes(krTickers) else emptyMap()

        return results.map { item ->
            if (item.market == "KR") {
                quotes[item.ticker]?.let { quote ->
                    item.copy(price = quote.currentPrice, changeRate = quote.changeRate)
                } ?: item
            } else {
                item
            }
        }
    }
}
