package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service

/** Compatibility envelope. Uncalibrated numeric scores are retired, never filled with neutral values. */
@Service
class CompositeRiskService {
    fun fromReport(report: MarketEvidenceReport): CompositeRiskSignal {
        val level = when (report.riskLevel) {
            "HIGH" -> "고위험"
            "ELEVATED" -> "경계"
            "NORMAL" -> "뚜렷한 경보 없음"
            else -> "판단 보류"
        }
        return CompositeRiskSignal(
            score = null, score100 = null, level = level,
            headline = when (report.riskLevel) {
                "HIGH" -> "강한 위험 신호가 관측됐습니다. 관련 시세와 확인된 보도를 함께 살펴보세요."
                "ELEVATED" -> "일부 위험 신호가 커졌습니다. 변동 폭과 주요 일정을 확인해 주세요."
                "NORMAL" -> "수집된 자료에서 뚜렷한 위험 경보는 확인되지 않았습니다. 개별 종목 위험과는 다릅니다."
                else -> "위험을 판단할 최신 자료가 부족합니다. 위험이 낮다는 의미는 아닙니다."
            },
            components = emptyList(),
            description = "검증된 변동성·유가·최근 보도에 대한 공통 외부 위험 경보입니다. 시장 방향이나 손실 확률이 아닙니다.",
            methodology = "브리프와 같은 검증된 입력을 사용합니다. 결측값을 중립으로 대체하지 않으며 실험 지표는 반영하지 않습니다. 기존 수치 점수와 개인 가중치는 사용하지 않습니다.",
            asOf = report.asOf, riskLevel = report.riskLevel, rulesVersion = report.rulesVersion,
        )
    }
}
