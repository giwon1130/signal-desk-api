package com.giwon.signaldesk.features.market.application

// 컴포넌트 라벨 — enum 엔트리 생성자에서 참조하므로 top-level(enum 초기화 전 준비됨)에 둔다.
const val LBL_VIX = "VIX 변동성"
const val LBL_KR = "한국 지수 변동"
const val LBL_FX = "원/달러 환율"
const val LBL_RATE = "미 10년물 금리"
const val LBL_PIZZ = "PizzINT 종합"
const val LBL_NEWS = "뉴스 키워드"

/**
 * 시장 분위기(합성 위험도) 가중 프로파일 — PRO 전용 커스터마이징.
 *
 * 각 프리셋은 컴포넌트 라벨별 '가중 배수'를 정의한다. [CompositeRiskService.assemble] 에서 기본 가중에
 * 곱한 뒤 합이 1이 되도록 재정규화하므로, 어느 관점(통합/한국/미국)에든 동일하게 적용된다.
 * FREE 사용자는 항상 [BALANCED] 로 고정(호출부 게이팅).
 */
enum class RiskWeightPreset(
    val label: String,
    val description: String,
    private val multipliers: Map<String, Double>,
) {
    BALANCED("균형", "기본 가중 — 시장 영향력 순 차등", emptyMap()),
    FX_SENSITIVE("환율 민감", "원/달러 환율 비중을 강하게", mapOf(LBL_FX to 1.7)),
    RATE_SENSITIVE("금리 민감", "미 10년물 금리 비중을 강하게", mapOf(LBL_RATE to 1.7)),
    DEFENSIVE(
        "방어형", "변동성·거시 위험을 더 크게 반영(보수적)",
        mapOf(LBL_VIX to 1.4, LBL_FX to 1.3, LBL_RATE to 1.3, LBL_KR to 1.3, LBL_NEWS to 0.8, LBL_PIZZ to 0.7),
    ),
    AGGRESSIVE(
        "공격형", "뉴스·지정학 노이즈는 줄이고 가격·변동성 위주",
        mapOf(LBL_NEWS to 0.5, LBL_PIZZ to 0.4, LBL_FX to 0.8, LBL_RATE to 0.8, LBL_KR to 1.2),
    ),

    /** 지표별 배수를 사용자가 직접 지정. 실제 배수는 저장된 customWeights 에서 온다(여기 multipliers 는 비움). */
    CUSTOM("직접 설정", "지표별 비중을 직접 조정", emptyMap());

    /** 컴포넌트 라벨 → 가중 배수(미지정 1.0). CUSTOM 은 [RiskWeightProfile] 가 customWeights 로 덮어쓴다. */
    fun multiplierFor(label: String): Double = multipliers[label] ?: 1.0

    companion object {
        fun fromOrDefault(name: String?): RiskWeightPreset =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: BALANCED
    }
}

/** 조정 가능한 지표(컴포넌트) 카탈로그 — 슬라이더 UI 용. id = 서버 컴포넌트 라벨. */
data class RiskWeightFactor(val id: String, val label: String)

val RISK_WEIGHT_FACTORS = listOf(
    RiskWeightFactor(LBL_KR, "한국 지수"),
    RiskWeightFactor(LBL_FX, "원/달러 환율"),
    RiskWeightFactor(LBL_RATE, "미 10년물 금리"),
    RiskWeightFactor(LBL_VIX, "美 변동성(VIX)"),
    RiskWeightFactor(LBL_NEWS, "뉴스 위험도"),
    RiskWeightFactor(LBL_PIZZ, "시장 신호(PizzINT)"),
)

/** 사용자 선택 = 프리셋 + (CUSTOM 일 때) 지표별 배수. customWeights 미지정 라벨은 1.0. */
data class RiskWeightSelection(
    val preset: RiskWeightPreset,
    val customWeights: Map<String, Double> = emptyMap(),
) {
    /** 적용할 배수 함수. CUSTOM 이면 customWeights(미지정 1.0), 아니면 프리셋 배수. */
    fun multiplierOf(label: String): Double =
        if (preset == RiskWeightPreset.CUSTOM) customWeights[label] ?: 1.0
        else preset.multiplierFor(label)

    companion object {
        val BALANCED = RiskWeightSelection(RiskWeightPreset.BALANCED)

        /** 입력 배수 정제 — 알려진 라벨만, [0, 3] 클램프. 빈/전부 0 이면 빈 맵(=BALANCED 효과). */
        fun sanitize(raw: Map<String, Double>?): Map<String, Double> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val known = RISK_WEIGHT_FACTORS.map { it.id }.toSet()
            return raw.filterKeys { it in known }
                .mapValues { it.value.coerceIn(0.0, 3.0) }
        }
    }
}

/** 응답 노출용 — 현재 적용 프리셋/커스텀 + 커스터마이징 가능 여부 + 선택지·지표 카탈로그. */
data class RiskWeightInfo(
    val preset: String,              // 현재 적용 중인 프리셋 (FREE 는 항상 BALANCED)
    val customizable: Boolean,       // PRO 여부 — false 면 앱에서 잠금+업그레이드 유도
    val options: List<RiskWeightOption>,
    val factors: List<RiskWeightFactor>,          // CUSTOM 슬라이더용 지표 카탈로그
    val customWeights: Map<String, Double>,       // 현재 커스텀 배수(라벨→배수). 미지정 라벨은 1.0
) {
    companion object {
        val OPTIONS = RiskWeightPreset.entries.map {
            RiskWeightOption(id = it.name, label = it.label, description = it.description)
        }

        fun of(selection: RiskWeightSelection, customizable: Boolean) =
            RiskWeightInfo(
                preset = selection.preset.name,
                customizable = customizable,
                options = OPTIONS,
                factors = RISK_WEIGHT_FACTORS,
                customWeights = selection.customWeights,
            )
    }
}

data class RiskWeightOption(val id: String, val label: String, val description: String)
