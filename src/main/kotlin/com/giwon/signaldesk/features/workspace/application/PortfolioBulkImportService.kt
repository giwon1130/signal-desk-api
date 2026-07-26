package com.giwon.signaldesk.features.workspace.application

import com.giwon.signaldesk.features.plan.PlanService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class PortfolioImportPositionDraft(
    val market: String,
    val ticker: String,
    val name: String,
    val buyPrice: Int,
    val currentPrice: Int,
    val quantity: Int,
)

@Service
class PortfolioBulkImportService(
    private val repository: SignalDeskWorkspaceRepository,
    private val workspaceService: WorkspaceService,
    @Autowired(required = false) private val planService: PlanService? = null,
) {
    /**
     * 사용자가 기기 안에서 OCR 후 직접 확인한 종목만 한 트랜잭션으로 저장한다.
     * 같은 market+ticker 는 기존 포지션을 갱신하고 목표가·손절가는 보존한다.
     */
    @Transactional
    fun save(userId: UUID, drafts: List<PortfolioImportPositionDraft>): List<WorkspaceHoldingPosition> {
        require(drafts.isNotEmpty()) { "등록할 종목을 선택해 주세요." }
        require(drafts.size <= MAX_IMPORT_SIZE) { "한 번에 최대 ${MAX_IMPORT_SIZE}개까지 등록할 수 있어요." }
        val unique = drafts.associateBy { key(it.market, it.ticker) }.values.toList()
        unique.forEach {
            require(it.market in setOf("KR", "US") && it.ticker.isNotBlank() && it.name.isNotBlank()) { "종목 정보를 확인해 주세요." }
            require(it.buyPrice > 0 && it.currentPrice > 0 && it.quantity > 0) { "매수가·현재가·수량은 0보다 커야 해요." }
        }

        val existing = repository.loadPortfolioPositions(userId)
        val existingByKey = existing.associateBy { key(it.market, it.ticker) }
        var count = existing.size
        unique.filter { key(it.market, it.ticker) !in existingByKey }.forEach {
            planService?.assertCanAdd(userId, PlanService.Resource.HOLDINGS, count)
            count += 1
        }

        return unique.map { draft ->
            val prior = existingByKey[key(draft.market, draft.ticker)]
            workspaceService.savePortfolioPosition(
                userId = userId,
                id = prior?.id.orEmpty(),
                market = draft.market,
                ticker = draft.ticker,
                name = draft.name,
                buyPrice = draft.buyPrice,
                currentPrice = draft.currentPrice,
                quantity = draft.quantity,
                targetPrice = prior?.targetPrice,
                stopLossPrice = prior?.stopLossPrice,
            )
        }
    }

    private fun key(market: String, ticker: String) = "${market.uppercase()}:${ticker.uppercase()}"

    private companion object {
        const val MAX_IMPORT_SIZE = 50
    }
}
