package com.giwon.signaldesk.features.reading.application

import com.giwon.signaldesk.features.market.application.StockSearchService
import org.springframework.stereotype.Service

/**
 * 리딩 글 본문에서 종목 후보를 추출한다.
 *
 * 오탐 방지: §12 결정 "종목 오탐 = 작성자 확인 필수" — 본 검출기는 **후보 제안**만 하고,
 * 실제 콜 등록은 작성자가 확정한 목록으로만 진행한다(Phase C). 따라서 과검출보다는
 * 신뢰도(confidence)를 같이 실어 보내 UI 에서 정렬·확인하게 한다.
 *
 * 신호 종류:
 *  - 캐시태그  `$AAPL`, `$005930`   → HIGH (작성자가 명시)
 *  - 6자리 코드 `005930`            → HIGH (KR 종목코드 형태)
 *  - 대괄호 표기 `[삼성전자]`        → HIGH (작성자가 명시)
 *  - 일반 명사 토큰 (한글/영문)       → 정확 명칭 일치 시 MEDIUM, 그 외 제외
 */
@Service
class StockMentionDetector(
    private val search: StockSearchService,
) {
    data class DetectedMention(
        val ticker: String,
        val name: String,
        val market: String,        // KR | US
        val matchedText: String,   // 본문에서 잡힌 원문 조각
        val confidence: String,    // HIGH | MEDIUM
    )

    private val cashtag = Regex("""\$([A-Za-z]{1,6}|\d{6})""")
    private val krCode = Regex("""(?<![A-Za-z0-9])(\d{6})(?![A-Za-z0-9])""")
    private val bracket = Regex("""[\[(]([가-힣A-Za-z0-9 ]{2,20})[\])]""")
    // 한글 2자 이상 또는 영문 2자 이상 토큰 (일반 명칭 후보).
    private val nameToken = Regex("""[가-힣]{2,12}|[A-Za-z]{2,12}""")

    fun detect(body: String): List<DetectedMention> {
        if (body.isBlank()) return emptyList()
        val out = LinkedHashMap<String, DetectedMention>()  // dedupe by "market:ticker"

        fun add(m: DetectedMention) {
            val key = "${m.market}:${m.ticker}"
            // 더 높은 confidence 가 들어오면 교체.
            val prev = out[key]
            if (prev == null || (prev.confidence == "MEDIUM" && m.confidence == "HIGH")) out[key] = m
        }

        // 1) 캐시태그 — 명시적, HIGH
        cashtag.findAll(body).forEach { mr ->
            val q = mr.groupValues[1]
            resolveExact(q)?.let { add(it.copy(matchedText = mr.value, confidence = "HIGH")) }
        }

        // 2) 6자리 코드 — KR 종목코드, HIGH
        krCode.findAll(body).forEach { mr ->
            val code = mr.groupValues[1]
            resolveExact(code)?.takeIf { it.market == "KR" }
                ?.let { add(it.copy(matchedText = code, confidence = "HIGH")) }
        }

        // 3) 대괄호/괄호 표기 — 작성자 명시, HIGH
        bracket.findAll(body).forEach { mr ->
            val token = mr.groupValues[1].trim()
            resolveExact(token)?.let { add(it.copy(matchedText = mr.value, confidence = "HIGH")) }
        }

        // 4) 일반 토큰 — 정확 명칭 일치만 MEDIUM 으로. (과검출 방지)
        nameToken.findAll(body)
            .map { it.value }
            .filter { it.length >= 2 }
            .distinct()
            .take(40)                       // 본문이 길어도 검색 호출 상한
            .forEach { token ->
                resolveExactByName(token)?.let { add(it.copy(matchedText = token, confidence = "MEDIUM")) }
            }

        return out.values.toList()
    }

    /** 코드/티커/명칭 어느 것이든 검색해서 최상위가 입력과 정확히 대응하면 반환. */
    private fun resolveExact(query: String): DetectedMention? {
        val q = query.trim()
        if (q.length < 2) return null
        val hit = search.search(q, market = null, limit = 5).firstOrNull { r ->
            r.ticker.equals(q, true) || r.name.equals(q, true)
        } ?: return null
        return DetectedMention(hit.ticker, hit.name, hit.market, q, "HIGH")
    }

    /** 명칭 정확 일치만 인정 (부분 일치 제외 — 오탐 방지). */
    private fun resolveExactByName(token: String): DetectedMention? {
        val hit = search.search(token, market = null, limit = 5)
            .firstOrNull { it.name.equals(token, true) } ?: return null
        return DetectedMention(hit.ticker, hit.name, hit.market, token, "MEDIUM")
    }
}
