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

    @Test
    fun `publishedAt 은 highlights 에 그대로 carry`() {
        // Phase 5 에서 추가된 publishedAt 필드 — RSS pubDate → ISO-8601 변환
        // NewsSentimentBuilder 가 그 값을 highlights 에 잃지 않고 전달하는지 검증.
        val iso = "2026-04-25T10:24:00Z"
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
