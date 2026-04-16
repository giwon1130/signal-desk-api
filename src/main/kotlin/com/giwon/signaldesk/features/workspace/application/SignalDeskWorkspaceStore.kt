package com.giwon.signaldesk.features.workspace.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Component
@Conditional(FileStoreCondition::class)
class SignalDeskWorkspaceStore(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.store.path:./data/signal-desk-store.json}") private val storePath: String,
) : SignalDeskWorkspaceRepository {
    private val lock = Any()
    private lateinit var resolvedPath: Path

    @PostConstruct
    fun init() {
        resolvedPath = Path.of(storePath).toAbsolutePath().normalize()
        Files.createDirectories(resolvedPath.parent)
        if (!Files.exists(resolvedPath)) {
            write(StoreFile())
        }
    }

    override fun loadWatchlist(): List<WorkspaceWatchItem> = synchronized(lock) { read().watchlist }

    override fun saveWatchItem(item: WorkspaceWatchItem): WorkspaceWatchItem = synchronized(lock) {
        val current = read()
        val nextItem = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() })
        val updated = current.copy(
            watchlist = current.watchlist
                .filterNot { it.id == nextItem.id }
                .plus(nextItem)
                .sortedBy { it.name }
        )
        write(updated)
        nextItem
    }

    override fun deleteWatchItem(id: String) = synchronized(lock) {
        val current = read()
        write(current.copy(watchlist = current.watchlist.filterNot { it.id == id }))
    }

    override fun loadPortfolioPositions(): List<WorkspaceHoldingPosition> = synchronized(lock) { read().portfolioPositions }

    override fun savePortfolioPosition(position: WorkspaceHoldingPosition): WorkspaceHoldingPosition = synchronized(lock) {
        val current = read()
        val nextPosition = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        val updated = current.copy(
            portfolioPositions = current.portfolioPositions
                .filterNot { it.id == nextPosition.id }
                .plus(nextPosition)
                .sortedBy { it.name }
        )
        write(updated)
        nextPosition
    }

    override fun deletePortfolioPosition(id: String) = synchronized(lock) {
        val current = read()
        write(current.copy(portfolioPositions = current.portfolioPositions.filterNot { it.id == id }))
    }

    override fun loadPaperPositions(): List<WorkspacePaperPosition> = synchronized(lock) { read().paperPositions }

    override fun savePaperPosition(position: WorkspacePaperPosition): WorkspacePaperPosition = synchronized(lock) {
        val current = read()
        val nextPosition = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        val updated = current.copy(
            paperPositions = current.paperPositions
                .filterNot { it.id == nextPosition.id }
                .plus(nextPosition)
                .sortedBy { it.name }
        )
        write(updated)
        nextPosition
    }

    override fun deletePaperPosition(id: String) = synchronized(lock) {
        val current = read()
        write(current.copy(paperPositions = current.paperPositions.filterNot { it.id == id }))
    }

    override fun loadPaperTrades(): List<WorkspacePaperTrade> = synchronized(lock) { read().paperTrades }

    override fun savePaperTrade(trade: WorkspacePaperTrade): WorkspacePaperTrade = synchronized(lock) {
        val current = read()
        val nextTrade = trade.copy(id = trade.id.ifBlank { UUID.randomUUID().toString() })
        val updated = current.copy(
            paperTrades = current.paperTrades
                .filterNot { it.id == nextTrade.id }
                .plus(nextTrade)
                .sortedByDescending { it.tradeDate }
        )
        write(updated)
        nextTrade
    }

    override fun deletePaperTrade(id: String) = synchronized(lock) {
        val current = read()
        write(current.copy(paperTrades = current.paperTrades.filterNot { it.id == id }))
    }

    override fun loadAiPicks(): List<WorkspaceAiPick> = synchronized(lock) { read().aiPicks }

    override fun saveAiPick(pick: WorkspaceAiPick): WorkspaceAiPick = synchronized(lock) {
        val current = read()
        val nextPick = pick.copy(id = pick.id.ifBlank { UUID.randomUUID().toString() })
        val updated = current.copy(
            aiPicks = current.aiPicks
                .filterNot { it.id == nextPick.id }
                .plus(nextPick)
                .sortedByDescending { it.confidence }
        )
        write(updated)
        nextPick
    }

    override fun deleteAiPick(id: String) = synchronized(lock) {
        val current = read()
        write(current.copy(aiPicks = current.aiPicks.filterNot { it.id == id }))
    }

    override fun loadAiTrackRecords(): List<WorkspaceAiTrackRecord> = synchronized(lock) { read().aiTrackRecords }

    override fun saveAiTrackRecord(record: WorkspaceAiTrackRecord): WorkspaceAiTrackRecord = synchronized(lock) {
        val current = read()
        val nextRecord = record.copy(id = record.id.ifBlank { UUID.randomUUID().toString() })
        val updated = current.copy(
            aiTrackRecords = current.aiTrackRecords
                .filterNot { it.id == nextRecord.id }
                .plus(nextRecord)
                .sortedByDescending { it.recommendedDate }
        )
        write(updated)
        nextRecord
    }

    override fun deleteAiTrackRecord(id: String) = synchronized(lock) {
        val current = read()
        write(current.copy(aiTrackRecords = current.aiTrackRecords.filterNot { it.id == id }))
    }

    private fun read(): StoreFile {
        return if (!Files.exists(resolvedPath)) {
            StoreFile()
        } else {
            runCatching { objectMapper.readValue<StoreFile>(resolvedPath.toFile()) }.getOrElse { StoreFile() }
        }
    }

    private fun write(storeFile: StoreFile) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(resolvedPath.toFile(), storeFile)
    }
}

data class StoreFile(
    val watchlist: List<WorkspaceWatchItem> = emptyList(),
    val portfolioPositions: List<WorkspaceHoldingPosition> = emptyList(),
    val paperPositions: List<WorkspacePaperPosition> = emptyList(),
    val paperTrades: List<WorkspacePaperTrade> = emptyList(),
    val aiPicks: List<WorkspaceAiPick> = emptyList(),
    val aiTrackRecords: List<WorkspaceAiTrackRecord> = emptyList(),
)

data class WorkspaceWatchItem(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val price: Int,
    val changeRate: Double,
    val sector: String,
    val stance: String,
    val note: String,
)

data class WorkspaceHoldingPosition(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val buyPrice: Int,
    val currentPrice: Int,
    val quantity: Int,
    val profitAmount: Int,
    val evaluationAmount: Int,
    val profitRate: Double,
)

data class WorkspacePaperPosition(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val averagePrice: Int,
    val currentPrice: Int,
    val quantity: Int,
    val returnRate: Double,
)

data class WorkspacePaperTrade(
    val id: String = "",
    val tradeDate: String,
    val side: String,
    val market: String,
    val ticker: String,
    val name: String,
    val price: Int,
    val quantity: Int,
)

data class WorkspaceAiPick(
    val id: String = "",
    val market: String,
    val ticker: String,
    val name: String,
    val basis: String,
    val confidence: Int,
    val note: String,
    val expectedReturnRate: Double,
)

data class WorkspaceAiTrackRecord(
    val id: String = "",
    val recommendedDate: String,
    val market: String,
    val ticker: String,
    val name: String,
    val entryPrice: Int,
    val latestPrice: Int,
    val realizedReturnRate: Double,
    val success: Boolean,
)
