package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.media.application.GeminiClient
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

/**
 * 뉴스 헤드라인 → 투자심리 톤(+1 긍정 / 0 중립 / -1 부정) 분류기.
 *
 * 1순위 = Gemini 배치 분류. 키워드 휴리스틱의 한계(영어 헤드라인 미스, "위기 해소"·"우려 완화" 같은
 * 부정어 문맥 오판, 협소 어휘)를 LLM 으로 해소한다. Gemini 비활성/실패/응답불량이면 키워드로 폴백.
 *
 * 시장 단위 공용(유저별 아님)이라 [news-sentiment] 캐시(15분)로 묶어 뉴스 갱신당 1회만 호출 → 쿼터 부담 작음.
 * self-invocation 캐시 우회를 피하려 분류만 별도 빈으로 분리([NewsSentimentService] 가 주입해 호출).
 */
@Component
class NewsToneClassifier(
    private val geminiClient: GeminiClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 제목 → 톤(+1/0/-1). 캐시 키 = 시장 + 표본 크기 + 제목 해시. 빈 입력은 비캐시. */
    @Cacheable(cacheNames = ["news-sentiment"], key = "#market + ':' + #titles.size() + ':' + #titles.hashCode()", unless = "#result.isEmpty()")
    fun classify(market: String, titles: List<String>): Map<String, Int> {
        if (titles.isEmpty()) return emptyMap()
        runCatching { geminiClassify(market, titles) }
            .onFailure { log.debug("Gemini 뉴스 톤 분류 실패 — 키워드 폴백. err={}", it.message) }
            .getOrNull()
            ?.let { return it }
        return titles.associateWith { keywordTone(it) }
    }

    /** 키워드 휴리스틱(폴백/회귀 호환). visibility=internal: 테스트에서 직접 검증. */
    internal fun keywordTone(title: String): Int {
        val lower = title.lowercase()
        val pos = POSITIVE_KEYWORDS.count { lower.contains(it) }
        val neg = NEGATIVE_KEYWORDS.count { lower.contains(it) }
        return when {
            pos > neg -> 1
            neg > pos -> -1
            else -> 0
        }
    }

    /** Gemini 배치 분류 — 입력 순서대로 정수 배열을 받아 제목에 매핑. 실패/형식불량/길이불일치면 null(→ 폴백). */
    private fun geminiClassify(market: String, titles: List<String>): Map<String, Int>? {
        if (!geminiClient.isEnabled()) return null
        val marketName = if (market.equals("US", ignoreCase = true)) "미국" else "한국"
        val numbered = titles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val prompt = """
            다음은 $marketName 주식시장 뉴스 헤드라인이야. 각 헤드라인이 시장 투자심리에 주는 톤을 판정해줘.
            규칙:
            - 호재/투자심리 개선 = 1, 악재/투자심리 악화 = -1, 혼재·무관·판단불가 = 0
            - 문맥을 봐: "위기 해소", "우려 완화", "낙폭 만회", "공포 진정"처럼 부정 단어가 들어가도 의미가 긍정이면 1로.
            - 영어 헤드라인도 같은 기준으로 판정.
            출력: 입력과 같은 길이의 JSON 정수 배열만. 입력 순서 유지. 예시 [1,-1,0,1]

            헤드라인:
            $numbered
        """.trimIndent()
        val raw = geminiClient.generateText(prompt, timeoutSeconds = 20, maxOutputTokens = 1024) ?: return null
        val node = runCatching { objectMapper.readTree(raw) }.getOrNull() ?: return null
        val arr = when {
            node.isArray -> node
            else -> node.firstOrNull { it.isArray }   // {"result":[...]} 류 방어
        } ?: return null
        if (arr.size() != titles.size) return null    // 길이 불일치 → 폴백
        val tones = arr.map { n ->
            (if (n.isNumber) n.asInt() else n.asText().trim().toIntOrNull() ?: 0).coerceIn(-1, 1)
        }
        return titles.zip(tones).toMap()
    }

    companion object {
        private val POSITIVE_KEYWORDS = listOf(
            "급등", "강세", "호재", "수혜", "최고치", "신고가", "반등", "상승", "회복", "낙관", "기대",
            "돌파", "역대급", "초강세", "랠리", "사상최고", "호조", "성장", "확대", "개선",
        )
        private val NEGATIVE_KEYWORDS = listOf(
            "급락", "약세", "악재", "쇼크", "최저치", "하락", "손실", "위기", "우려", "경고", "부진",
            "하향", "충격", "공포", "위축", "하한가", "패닉", "긴축", "감소", "악화",
        )
    }
}
