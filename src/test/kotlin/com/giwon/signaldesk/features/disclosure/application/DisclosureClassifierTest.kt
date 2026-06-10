package com.giwon.signaldesk.features.disclosure.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 제목 기반 중요도 분류 회귀 보호.
 *  - HIGH: 주가에 직접 작용하는 사안. 정정([기재정정]) 래퍼가 붙어도 핵심이 중요하면 HIGH.
 *  - LOW:  IR개최/지분보고/정기보고서/주총소집 등 루틴 → 푸시 제외 대상.
 *  - MEDIUM: 그 외 + 분류 불명확(알려지지 않은 공시는 숨기지 않음).
 */
class DisclosureClassifierTest {

    private fun classify(s: String) = DisclosureClassifier.classify(s)

    @Test
    fun `주가영향 핵심 사안은 HIGH`() {
        listOf(
            "단일판매ㆍ공급계약체결",
            "[기재정정]단일판매ㆍ공급계약체결",
            "주요사항보고서(자기주식취득결정)",
            "주요사항보고서(유상증자결정)",
            "무상증자결정",
            "주요사항보고서(전환사채권발행결정)",
            "주요사항보고서(신주인수권부사채권발행결정)",
            "영업(잠정)실적(공정공시)",
            "매출액또는손익구조30%(대규모법인은15%)이상변동",
            "회사합병결정",
            "회사분할결정",
            "최대주주변경",
            "횡령ㆍ배임혐의발생",
            "주요사항보고서(파산신청)",
            "상장폐지결정",
            "주식분할결정",
            "타법인주식및출자증권취득결정",
        ).forEach {
            assertThat(classify(it)).`as`(it).isEqualTo(DisclosureImportance.HIGH)
        }
    }

    @Test
    fun `루틴ㆍ신고성 공시는 LOW`() {
        listOf(
            "기업설명회(IR)개최(안내공시)",
            "주식등의대량보유상황보고서(일반)",
            "임원ㆍ주요주주특정증권등소유상황보고서",
            "의결권대리행사권유참고서류",
            "사업보고서(2025.12)",
            "분기보고서(2026.03)",
            "반기보고서",
            "감사보고서",
            "주주총회소집공고",
            "결산실적공시예고(안내공시)",
            "주식명의개서정지(주주명부폐쇄)",
            "기업지배구조보고서공시",
        ).forEach {
            assertThat(classify(it)).`as`(it).isEqualTo(DisclosureImportance.LOW)
        }
    }

    @Test
    fun `분류 불명확한 공시는 기본 MEDIUM`() {
        listOf(
            "현금ㆍ현물배당결정",
            "소송등의제기ㆍ신청(경영권분쟁소송제외)",
            "특허권취득",
            "신규시설투자등",
        ).forEach {
            assertThat(classify(it)).`as`(it).isEqualTo(DisclosureImportance.MEDIUM)
        }
    }
}
