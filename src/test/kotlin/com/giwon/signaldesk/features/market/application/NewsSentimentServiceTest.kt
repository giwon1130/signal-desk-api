package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull

class NewsSentimentBuilderTest {

    private fun news(
        market: String = "KR",
        title: String,
        publishedAt: String? = null,
    ) = MarketNews(
        market = market,
        title = title,
        source = "테스트",
        url = "https://example.com/$title",
        impact = "테스트",
        publishedAt = publishedAt,
    )

    @Test
    fun `긍정 키워드 우세하면 라벨 긍정 + score 60 이상`() {
        val result = NewsSentimentBuilder.build("KR", listOf(
            news(title = "코스피 급등 신고가 돌파"),
            news(title = "외국인 매수 강세 호조"),
            news(title = "반도체 호재 수혜"),
            news(title = "단순 기업 공시"),
        ))
        assertEquals("긍정", result.label)
        assertTrue(result.score >= 60, "score=${result.score}")
        assertTrue(result.positiveCount >= 3)
    }

    @Test
    fun `부정 키워드 우세하면 라벨 부정 + score 40 이하`() {
        val result = NewsSentimentBuilder.build("KR", listOf(
            news(title = "코스피 급락 쇼크"),
            news(title = "외국인 매도 약세"),
            news(title = "긴축 우려 부진"),
            news(title = "공포 패닉 손실 확대"),
        ))
        assertEquals("부정", result.label)
        assertTrue(result.score <= 40, "score=${result.score}")
    }

    @Test
    fun `다른 market 뉴스는 무시`() {
        val result = NewsSentimentBuilder.build("KR", listOf(
            news(market = "US", title = "S&P 급등 강세"),
            news(market = "US", title = "NASDAQ 호재"),
            news(market = "KR", title = "단순 공시"),
        ))
        // KR 만 1건 + 키워드 없음 → 중립 50 근처
        assertEquals("중립", result.label)
        assertEquals(0, result.positiveCount)
        assertEquals(1, result.neutralCount)
    }

    private val kst = java.time.ZoneId.of("Asia/Seoul")
    private fun isoOn(date: java.time.LocalDate) =
        date.atTime(10, 24).atZone(kst).toInstant().toString()

    @Test
    fun `publishedAt 은 highlights 에 그대로 carry`() {
        // Phase 5 에서 추가된 publishedAt 필드 — RSS pubDate → ISO-8601 변환
        // NewsSentimentBuilder 가 그 값을 highlights 에 잃지 않고 전달하는지 검증.
        // (오늘 필터가 생겼으므로 '오늘' 발행분으로 만들어 필터를 통과시킨다.)
        val iso = isoOn(java.time.LocalDate.now(kst))
        val result = NewsSentimentBuilder.build("KR", listOf(
            news(title = "코스피 급등", publishedAt = iso),
            news(title = "단순 뉴스", publishedAt = null),
        ))
        val highlightWithTime = result.highlights.first { it.title == "코스피 급등" }
        val highlightWithoutTime = result.highlights.first { it.title == "단순 뉴스" }
        assertEquals(iso, highlightWithTime.publishedAt)
        assertNotNull(highlightWithoutTime)
        assertEquals(null, highlightWithoutTime.publishedAt)
    }

    @Test
    fun `어제 발행 뉴스는 오늘 필터에서 제외되고 오늘+날짜없음은 유지`() {
        val today = java.time.LocalDate.now(kst)
        val result = NewsSentimentBuilder.build("KR", listOf(
            news(title = "오늘 뉴스 급등", publishedAt = isoOn(today)),
            news(title = "어제 뉴스 급락", publishedAt = isoOn(today.minusDays(1))),
            news(title = "날짜없는 뉴스", publishedAt = null),
        ))
        val titles = result.highlights.map { it.title }
        assertTrue(titles.contains("오늘 뉴스 급등"), "오늘 뉴스 누락")
        assertTrue(titles.contains("날짜없는 뉴스"), "날짜없는 뉴스 누락(과필터)")
        assertTrue(!titles.contains("어제 뉴스 급락"), "어제 뉴스가 노출됨")
    }

    @Test
    fun `중립 다수에 묻히지 않고 부정 우세를 반영한다`() {
        // 부정 5 vs 긍정 2 인데 중립 헤드라인이 다수여도 라벨은 '부정' 이어야 한다.
        // (이전 구현: 분모=전체27 → 46 '중립'. 새 구현: 분모=톤7 → 33 '부정')
        val items = buildList {
            repeat(2) { add(news(title = "코스피 급등 강세 $it")) }
            repeat(5) { add(news(title = "코스피 급락 쇼크 $it")) }
            repeat(20) { add(news(title = "기업 정기 공시 $it")) }
        }
        val result = NewsSentimentBuilder.build("KR", items)
        assertEquals("부정", result.label, "score=${result.score}")
        assertTrue(result.score <= 40, "score=${result.score}")
        assertEquals(2, result.positiveCount)
        assertEquals(5, result.negativeCount)
    }

    @Test
    fun `하이라이트는 최대 15개로 제한`() {
        val items = (1..25).map { news(title = "코스피 급락 쇼크 $it") }
        val result = NewsSentimentBuilder.build("KR", items)
        assertEquals(15, result.highlights.size)
    }

    @Test
    fun `뉴스 0건이면 중립 score 50 + 분석 가능 뉴스 없어 rationale`() {
        val result = NewsSentimentBuilder.build("KR", emptyList())
        assertEquals("중립", result.label)
        assertEquals(50, result.score)
        assertEquals(0, result.highlights.size)
        assertTrue(result.rationale.contains("없"), "rationale=${result.rationale}")
    }
}
