package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 시장 분위기 가중 프리셋/커스텀 저장소 (PRO 커스터마이징). jdbc 스토어 모드에서만 빈 등록 —
 * 미존재(인메모리/테스트)면 호출부에서 null 처리 → 전원 BALANCED.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class RiskWeightPreferenceService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 저장된 선택(없으면 BALANCED). 알 수 없는 preset/배수가 들어와도 정규화로 흡수. */
    fun get(userId: UUID): RiskWeightSelection {
        val row = jdbc.query(
            "select preset, custom_weights from signal_desk_risk_weight_preference where user_id = ?::uuid",
            { rs, _ -> rs.getString("preset") to rs.getString("custom_weights") },
            userId.toString(),
        ).firstOrNull() ?: return RiskWeightSelection.BALANCED
        val preset = RiskWeightPreset.fromOrDefault(row.first)
        val custom = if (preset == RiskWeightPreset.CUSTOM) parseWeights(row.second) else emptyMap()
        return RiskWeightSelection(preset, custom)
    }

    /** preset/custom upsert. preset 정규화 + custom 정제(알려진 라벨·[0,3] 클램프). */
    fun update(userId: UUID, preset: String?, customWeights: Map<String, Double>?): RiskWeightSelection {
        val normalized = RiskWeightPreset.fromOrDefault(preset)
        val cleaned = if (normalized == RiskWeightPreset.CUSTOM) RiskWeightSelection.sanitize(customWeights) else emptyMap()
        val json = if (cleaned.isEmpty()) null else objectMapper.writeValueAsString(cleaned)
        jdbc.update(
            """
            insert into signal_desk_risk_weight_preference (user_id, preset, custom_weights, updated_at)
            values (?::uuid, ?, ?, now())
            on conflict (user_id) do update set preset = excluded.preset, custom_weights = excluded.custom_weights, updated_at = now()
            """.trimIndent(),
            userId.toString(),
            normalized.name,
            json,
        )
        return RiskWeightSelection(normalized, cleaned)
    }

    /** custom_weights JSON("{\"원/달러 환율\":1.7}") → 정제된 맵. 깨지면 빈 맵. */
    private fun parseWeights(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val raw: Map<String, Double> = objectMapper.readValue(
                json,
                objectMapper.typeFactory.constructMapType(HashMap::class.java, String::class.java, java.lang.Double::class.java),
            )
            RiskWeightSelection.sanitize(raw)
        }.onFailure { log.warn("custom_weights 파싱 실패: {}", it.message) }.getOrDefault(emptyMap())
    }
}
