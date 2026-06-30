package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.application.RiskWeightInfo
import com.giwon.signaldesk.features.market.application.RiskWeightPreferenceService
import com.giwon.signaldesk.features.market.application.RiskWeightSelection
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 시장 분위기 가중 프리셋 — 조회/변경. 변경은 PRO 전용(FREE 는 항상 BALANCED 고정).
 * jdbc 스토어 모드에서만 활성([RiskWeightPreferenceService] 존재 시).
 */
@RestController
@RequestMapping("/api/v1/me/risk-weight")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class RiskWeightPreferenceController(
    private val service: RiskWeightPreferenceService,
    private val authContext: AuthContext,
    private val planService: com.giwon.signaldesk.features.plan.PlanService,
) {
    @GetMapping
    fun get(@RequestHeader("Authorization") auth: String): RiskWeightInfo {
        val userId = authContext.requireUserId(auth)
        val pro = planService.isPro(userId)
        // FREE 는 저장값과 무관하게 BALANCED 로 노출(적용도 BALANCED).
        val selection = if (pro) service.get(userId) else RiskWeightSelection.BALANCED
        return RiskWeightInfo.of(selection, customizable = pro)
    }

    @PutMapping
    fun update(
        @RequestHeader("Authorization") auth: String,
        @RequestBody body: RiskWeightUpdateRequest,
    ): RiskWeightInfo {
        val userId = authContext.requireUserId(auth)
        require(planService.isPro(userId)) {
            "시장 분위기 가중치 커스터마이징은 PRO 플랜 전용이에요. PRO 로 업그레이드하면 조정할 수 있어요. 💎"
        }
        val saved = service.update(userId, body.preset, body.customWeights)
        return RiskWeightInfo.of(saved, customizable = true)
    }
}

/** customWeights = 라벨→배수(CUSTOM 일 때만 의미). preset != CUSTOM 이면 무시됨. */
data class RiskWeightUpdateRequest(val preset: String, val customWeights: Map<String, Double>? = null)
