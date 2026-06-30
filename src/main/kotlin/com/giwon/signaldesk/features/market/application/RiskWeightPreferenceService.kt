package com.giwon.signaldesk.features.market.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 시장 분위기 가중 프리셋 저장소 (PRO 커스터마이징). jdbc 스토어 모드에서만 빈 등록 —
 * 미존재(인메모리/테스트)면 호출부에서 null 처리 → 전원 BALANCED.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class RiskWeightPreferenceService(private val jdbc: JdbcTemplate) {

    /** 저장된 프리셋(없으면 BALANCED). 알 수 없는 값이 들어와도 fromOrDefault 가 BALANCED 로 흡수. */
    fun get(userId: UUID): RiskWeightPreset {
        val raw = jdbc.query(
            "select preset from signal_desk_risk_weight_preference where user_id = ?::uuid",
            { rs, _ -> rs.getString("preset") },
            userId.toString(),
        ).firstOrNull()
        return RiskWeightPreset.fromOrDefault(raw)
    }

    /** 프리셋 upsert. 입력은 fromOrDefault 로 정규화해 저장(유효하지 않으면 BALANCED). */
    fun update(userId: UUID, preset: String?): RiskWeightPreset {
        val normalized = RiskWeightPreset.fromOrDefault(preset)
        jdbc.update(
            """
            insert into signal_desk_risk_weight_preference (user_id, preset, updated_at)
            values (?::uuid, ?, now())
            on conflict (user_id) do update set preset = excluded.preset, updated_at = now()
            """.trimIndent(),
            userId.toString(),
            normalized.name,
        )
        return normalized
    }
}
