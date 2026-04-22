package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PickNewsMatcherTest {

    private val matcher = PickNewsMatcher()

    @Test
    fun `빈 뉴스면 null`() {
        assertNull(matcher.findMatch("KR", "삼성전자", "005930", emptyList()))
    }

    @Test
    fun `한국 숫자 티커가 title에 그대로 포함되면 최우선 매칭`() {
        val news = listOf(
            kr("시장 전반 상승", "https://a"),
            kr("005930 실적 서프라이즈", "https://target"),
            kr("삼성전자 이슈", "https://b"), // name 매칭 후보지만 티커 매칭이 우선
        )
        val match = matcher.findMatch("KR", "삼성전자", "005930", news)
        assertEquals("https://target", match?.url)
    }

    @Test
    fun `영문 티커는 단어 경계에서만 매칭 - AA가 AAPL 에 매칭되지 않아야`() {
        val news = listOf(us("AAPL earnings beat", "https://aapl"))
        assertNull(matcher.findMatch("US", "Alcoa", "AA", news))
    }

    @Test
    fun `영문 티커가 단어 경계에 있으면 매칭`() {
        val news = listOf(us("AAPL jumps on AI reveal", "https://aapl"))
        val match = matcher.findMatch("US", "Apple", "AAPL", news)
        assertEquals("https://aapl", match?.url)
    }

    @Test
    fun `2글자 이상 한글 이름 + 티커 미매칭이면 이름 fallback`() {
        val news = listOf(kr("삼성전자 반도체 수출 호조", "https://samsung"))
        val match = matcher.findMatch("KR", "삼성전자", "005930", news)
        assertEquals("https://samsung", match?.url)
    }

    @Test
    fun `1글자 이름은 오매칭 방지를 위해 매칭 생략`() {
        val news = listOf(kr("반도체 업황 개선", "https://a"))
        assertNull(matcher.findMatch("KR", "반", "000000", news))
    }

    @Test
    fun `같은 market 뉴스 우선 매칭`() {
        val news = listOf(
            us("삼성전자 ADR 하락", "https://us"),
            kr("삼성전자 신고가", "https://kr"),
        )
        val match = matcher.findMatch("KR", "삼성전자", "005930", news)
        assertEquals("https://kr", match?.url)
    }

    @Test
    fun `같은 market 뉴스 없으면 전체에서 fallback`() {
        val news = listOf(us("삼성전자 ADR 하락", "https://us"))
        val match = matcher.findMatch("KR", "삼성전자", "005930", news)
        assertNotNull(match)
    }

    @Test
    fun `이름이 title 에 없고 티커도 없으면 null`() {
        val news = listOf(kr("시장 전반 혼조", "https://a"))
        assertNull(matcher.findMatch("KR", "삼성전자", "005930", news))
    }

    private fun kr(title: String, url: String) = MarketNews("KR", title, "source", url, "")
    private fun us(title: String, url: String) = MarketNews("US", title, "source", url, "")
}
