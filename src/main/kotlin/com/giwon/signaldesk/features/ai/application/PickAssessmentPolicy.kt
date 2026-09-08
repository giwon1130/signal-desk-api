package com.giwon.signaldesk.features.ai.application

import kotlin.math.abs

/**
 * 시세 스냅샷을 이용한 검토용 필터. 임계값은 보수적 제품 기본값이며 백테스트된 알파가 아니다.
 * 뉴스/AI 확신도로 데이터 누락이나 가격 급변 차단을 덮어쓰지 않는다.
 */
object PickAssessmentPolicy {
    fun assess(candidate: PickCandidate): PickAssessment {
        val missing = buildList {
            if (candidate.market !in setOf("KR", "US")) add("지원 시장을 확인할 수 없어")
            if (candidate.price == null || !candidate.price.isFinite() || candidate.price <= 0) add("유효한 기준가가 없어")
            if (candidate.changeRate == null || !candidate.changeRate.isFinite()) add("당일 등락률을 확인할 수 없어")
        }
        if (missing.isNotEmpty()) return PickAssessment(PickDecision.INSUFFICIENT_DATA, emptyList(), missing)

        val change = requireNotNull(candidate.changeRate)
        val reasons = buildList {
            add("기준가와 당일 등락률을 확인했어")
            candidate.flowTag?.takeIf { it.isNotBlank() }?.let { add("수급 자료: $it") }
        }
        val blockers = when {
            abs(change) >= 10.0 -> listOf("당일 변동폭이 10% 이상이라 신규 진입 계획을 만들지 않아")
            change >= 6.0 -> listOf("당일 6% 이상 상승해 추격 위험을 먼저 확인해야 해")
            change <= -3.0 -> listOf("당일 3% 이상 하락해 낙폭 확대 여부를 더 지켜봐야 해")
            change < 0.0 && candidate.flowTag.isNullOrBlank() -> listOf("하락 중이고 확인된 순매수 수급 자료가 없어")
            else -> emptyList()
        }
        val decision = when {
            abs(change) >= 10.0 -> PickDecision.AVOID
            blockers.isNotEmpty() -> PickDecision.WATCH
            else -> PickDecision.REVIEW
        }
        return PickAssessment(decision, reasons, blockers)
    }
}
