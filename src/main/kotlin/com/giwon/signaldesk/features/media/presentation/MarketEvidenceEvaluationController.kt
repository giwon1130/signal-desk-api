package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.media.application.MarketEvidenceEvaluation
import com.giwon.signaldesk.features.media.application.MarketEvidenceEvaluationService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.*

/** Admin-only diagnostic contract; never returns raw archived inputs or news. */
@RestController
@RequestMapping("/api/v1/insights/evaluation")
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MarketEvidenceEvaluationController(
    private val service: MarketEvidenceEvaluationService,
    private val authContext: AuthContext,
    private val adminGuard: AdminGuard,
) {
    @GetMapping
    fun evaluate(@RequestHeader("Authorization", required = false) auth: String?,
                 @RequestParam(defaultValue = "30") days: Int): ApiResponse<MarketEvidenceEvaluation> {
        adminGuard.requireAdmin(authContext.requireUserId(auth))
        require(days in 1..90) { "days must be between 1 and 90" }
        return ApiResponse(true, service.evaluate(days))
    }
}
