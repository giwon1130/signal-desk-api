package com.giwon.signaldesk.features.market.application

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

/**
 * 기사 제목의 표현만 규칙으로 분류한다. 사실 확인, 투자심리 판단, 가격 전망이 아니며 LLM은 사용하지 않는다.
 */
@Component
class NewsToneClassifier {

    /** 제목 → 톤(+1/0/-1). 캐시 키 = 시장 + 표본 크기 + 제목 해시. 빈 입력은 비캐시. */
    @Cacheable(cacheNames = ["news-sentiment"], key = "#market + ':' + #titles.size() + ':' + #titles.hashCode()", unless = "#result.isEmpty()")
    fun classify(market: String, titles: List<String>): Map<String, Int> {
        if (titles.isEmpty()) return emptyMap()
        // Display-only lexical classification. Gemini does not decide market direction or news risk.
        return titles.associateWith { keywordTone(it) }
    }

    /** 키워드 휴리스틱(폴백/회귀 호환). visibility=internal: 테스트에서 직접 검증. */
    internal fun keywordTone(title: String): Int {
        val lower = title.lowercase()
            .replace(Regex("위기 해소|우려 완화|공포 진정|낙폭 만회|concerns ease|fears ease"), " 회복 ")
        val pos = POSITIVE_KEYWORDS.count { lower.contains(it) }
        val neg = NEGATIVE_KEYWORDS.count { lower.contains(it) }
        return when {
            pos > neg -> 1
            neg > pos -> -1
            else -> 0
        }
    }

    companion object {
        private val POSITIVE_KEYWORDS = listOf(
            "급등", "강세", "호재", "수혜", "최고치", "신고가", "반등", "상승", "회복", "낙관", "기대",
            "돌파", "초강세", "랠리", "사상최고", "호조", "성장", "개선", "rally", "surge", "gains",
        )
        private val NEGATIVE_KEYWORDS = listOf(
            "급락", "약세", "악재", "쇼크", "최저치", "하락", "손실", "위기", "우려", "경고", "부진",
            "하향", "충격", "공포", "위축", "하한가", "패닉", "긴축", "감소", "악화", "plunge", "slump", "losses",
        )
    }
}
