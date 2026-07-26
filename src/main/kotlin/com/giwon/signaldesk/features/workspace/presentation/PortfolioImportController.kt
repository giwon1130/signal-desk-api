package com.giwon.signaldesk.features.workspace.presentation

import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.market.presentation.ApiResponse
import com.giwon.signaldesk.features.workspace.application.PortfolioBulkImportService
import com.giwon.signaldesk.features.workspace.application.PortfolioImportPositionDraft
import com.giwon.signaldesk.features.workspace.application.WorkspaceHoldingPosition
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/workspace/portfolio/import")
class PortfolioImportController(
    private val authContext: AuthContext,
    private val bulkImportService: PortfolioBulkImportService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun commit(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: PortfolioImportRequest,
    ): ApiResponse<List<WorkspaceHoldingPosition>> {
        val userId = authContext.requireUserId(auth)
        val saved = bulkImportService.save(userId, request.positions.map(PortfolioImportPositionRequest::toDraft))
        log.info("portfolio import committed user={} positions={}", userId.toString().take(8), saved.size)
        return ApiResponse(true, saved)
    }
}

data class PortfolioImportRequest(
    @field:Size(min = 1, max = 50) @field:Valid val positions: List<PortfolioImportPositionRequest>,
)

data class PortfolioImportPositionRequest(
    @field:NotBlank val market: String,
    @field:NotBlank val ticker: String,
    @field:NotBlank val name: String,
    @field:Positive val buyPrice: Int,
    @field:Positive val currentPrice: Int,
    @field:Positive val quantity: Int,
) {
    fun toDraft() = PortfolioImportPositionDraft(
        market = market.trim().uppercase(),
        ticker = ticker.trim().uppercase(),
        name = name.trim(),
        buyPrice = buyPrice,
        currentPrice = currentPrice,
        quantity = quantity,
    )
}
