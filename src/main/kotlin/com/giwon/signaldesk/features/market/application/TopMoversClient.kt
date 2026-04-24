package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration

/**
 * 시장별 상승률/하락률 상위 종목 리스트 ("급등 / 급락").
 *
 * 데이터 소스: 네이버 금융의 시세 페이지 (상승/하락).
 *   - KOSPI 상승: https://finance.naver.com/sise/sise_rise.naver?sosok=0
 *   - KOSDAQ 상승: https://finance.naver.com/sise/sise_rise.naver?sosok=1
 *   - KOSPI 하락: https://finance.naver.com/sise/sise_fall.naver?sosok=0
 *   - KOSDAQ 하락: https://finance.naver.com/sise/sise_fall.naver?sosok=1
 *
 * 공식 JSON API 는 공개된 게 없어서 HTML 페이지를 정규식으로 파싱. 페이지는 EUC-KR 로 인코딩된다.
 *
 * 행 패턴 (tr 1개당):
 *   <a href="/item/main.naver?code=XXXXXX" class="tltle">종목명</a>
 *   ...<td class="number">현재가</td>
 *   ...<span class="tah p11 (red01|blue01)"> +12.34% (또는 -5.67%) </span>
 *
 * 실패(페이지 구조 변경 등) 시 빈 리스트. 상위 레이어에서 섹션을 숨긴다.
 */
@Component
class TopMoversClient(
    @Value("\${signal-desk.integrations.naver-top-movers.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.naver-top-movers.base-url:https://finance.naver.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchTopMovers(market: KoreanMarket, direction: Direction, limit: Int = 10): List<TopMover> {
        if (!enabled) return emptyList()

        val sosok = when (market) { KoreanMarket.KOSPI -> 0; KoreanMarket.KOSDAQ -> 1 }
        val path = when (direction) { Direction.GAINERS -> "sise_rise"; Direction.LOSERS -> "sise_fall" }

        val uri = URI.create("$baseUrl/sise/$path.naver?sosok=$sosok")
        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.naver.com/")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                log.warn("TopMovers non-2xx. status={}, uri={}", response.statusCode(), uri)
                return@runCatching emptyList<TopMover>()
            }
            val html = String(response.body(), Charset.forName("EUC-KR"))
            parseMovers(html, direction, limit)
        }.getOrElse {
            log.warn("TopMovers fetch exception. uri={}, err={}", uri, it.message)
            emptyList()
        }
    }

    private fun parseMovers(html: String, direction: Direction, limit: Int): List<TopMover> {
        // 네이버 페이지는 변동률 셀에 서로 다른 색 클래스를 쓴다:
        //   red01  → 상승 (sise_rise)
        //   nv01   → 하락 (sise_fall 의 주 행들)
        //   blue01 → 드물게 섞이는 소폭 하락 요약
        // direction 에 맞는 색만 골라서 실제 "상위 상승/하락" 만 노출한다.
        val expectColors = when (direction) {
            Direction.GAINERS -> setOf("red01")
            Direction.LOSERS  -> setOf("nv01", "blue01")
        }
        val rows = ROW_REGEX.findAll(html).toList()
        return rows.asSequence()
            .mapNotNull { match ->
                val code = match.groupValues[1]
                val name = match.groupValues[2].trim()
                val priceStr = match.groupValues[3].replace(",", "")
                val rateSign = match.groupValues[4] // red01=상승, blue01=하락
                val rateStr = match.groupValues[5].replace(",", "")

                if (code.isBlank() || name.isBlank()) return@mapNotNull null
                if (rateSign !in expectColors) return@mapNotNull null
                val price = priceStr.toDoubleOrNull()?.toInt() ?: 0
                val rate = rateStr.toDoubleOrNull() ?: return@mapNotNull null
                val signed = if (rateSign == "blue01" || rateSign == "nv01") -rate else rate

                TopMover(market = "KR", ticker = code, name = name, price = price, changeRate = signed)
            }
            .distinctBy { it.ticker }
            .take(limit)
            .toList()
    }

    enum class KoreanMarket { KOSPI, KOSDAQ }
    enum class Direction { GAINERS, LOSERS }

    companion object {
        // 네이버 시세 상승/하락 페이지의 각 tr 에서 필요한 필드만 추출.
        // "code=XYZ" class="tltle">이름</a> ... <td class="number">현재가</td> ... span class="tah p11 (red01|blue01)"> +rate% ... </span>
        private val ROW_REGEX = Regex(
            """code=([A-Za-z0-9]+)"\s+class="tltle">([^<]+)</a></td>\s*<td class="number">([\d,]+)</td>.*?tah\s+p11\s+(red01|blue01|nv01)">\s*[+-]?([\d.,]+)%""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}

data class TopMover(
    val market: String,      // "KR" / "US"
    val ticker: String,
    val name: String,
    val price: Int,
    val changeRate: Double,
)

data class TopMoversResponse(
    val generatedAt: String,
    val kospi: TopMoversBlock,
    val kosdaq: TopMoversBlock,
)

data class TopMoversBlock(
    val gainers: List<TopMover>,
    val losers: List<TopMover>,
)
