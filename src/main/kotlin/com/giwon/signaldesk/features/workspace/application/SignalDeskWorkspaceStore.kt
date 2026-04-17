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
        if (!Files.exists(resolvedPath)) write(StoreFile())
    }

    override fun loadWatchlist(): List<WorkspaceWatchItem> = synchronized(lock) { read().watchlist }

    override fun saveWatchItem(item: WorkspaceWatchItem): WorkspaceWatchItem = synchronized(lock) {
        val next = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(watchlist = read().watchlist.filterNot { it.id == next.id }.plus(next).sortedBy { it.name }))
        next
    }

    override fun deleteWatchItem(id: String) = synchronized(lock) {
        write(read().copy(watchlist = read().watchlist.filterNot { it.id == id }))
    }

    override fun loadPortfolioPositions(): List<WorkspaceHoldingPosition> = synchronized(lock) { read().portfolioPositions }

    override fun savePortfolioPosition(position: WorkspaceHoldingPosition): WorkspaceHoldingPosition = synchronized(lock) {
        val next = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(portfolioPositions = read().portfolioPositions.filterNot { it.id == next.id }.plus(next).sortedBy { it.name }))
        next
    }

    override fun deletePortfolioPosition(id: String) = synchronized(lock) {
        write(read().copy(portfolioPositions = read().portfolioPositions.filterNot { it.id == id }))
    }

    override fun loadPaperPositions(): List<WorkspacePaperPosition> = synchronized(lock) { read().paperPositions }

    override fun savePaperPosition(position: WorkspacePaperPosition): WorkspacePaperPosition = synchronized(lock) {
        val next = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(paperPositions = read().paperPositions.filterNot { it.id == next.id }.plus(next).sortedBy { it.name }))
        next
    }

    override fun deletePaperPosition(id: String) = synchronized(lock) {
        write(read().copy(paperPositions = read().paperPositions.filterNot { it.id == id }))
    }

    override fun loadPaperTrades(): List<WorkspacePaperTrade> = synchronized(lock) { read().paperTrades }

    override fun savePaperTrade(trade: WorkspacePaperTrade): WorkspacePaperTrade = synchronized(lock) {
        val next = trade.copy(id = trade.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(paperTrades = read().paperTrades.filterNot { it.id == next.id }.plus(next).sortedByDescending { it.tradeDate }))
        next
    }

    override fun deletePaperTrade(id: String) = synchronized(lock) {
        write(read().copy(paperTrades = read().paperTrades.filterNot { it.id == id }))
    }

    override fun loadAiPicks(): List<WorkspaceAiPick> = synchronized(lock) { read().aiPicks }

    override fun saveAiPick(pick: WorkspaceAiPick): WorkspaceAiPick = synchronized(lock) {
        val next = pick.copy(id = pick.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(aiPicks = read().aiPicks.filterNot { it.id == next.id }.plus(next).sortedByDescending { it.confidence }))
        next
    }

    override fun deleteAiPick(id: String) = synchronized(lock) {
        write(read().copy(aiPicks = read().aiPicks.filterNot { it.id == id }))
    }

    override fun loadAiTrackRecords(): List<WorkspaceAiTrackRecord> = synchronized(lock) { read().aiTrackRecords }

    override fun saveAiTrackRecord(record: WorkspaceAiTrackRecord): WorkspaceAiTrackRecord = synchronized(lock) {
        val next = record.copy(id = record.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(aiTrackRecords = read().aiTrackRecords.filterNot { it.id == next.id }.plus(next).sortedByDescending { it.recommendedDate }))
        next
    }

    override fun deleteAiTrackRecord(id: String) = synchronized(lock) {
        write(read().copy(aiTrackRecords = read().aiTrackRecords.filterNot { it.id == id }))
    }

    private fun read(): StoreFile =
        if (!Files.exists(resolvedPath)) StoreFile()
        else runCatching { objectMapper.readValue<StoreFile>(resolvedPath.toFile()) }.getOrElse { StoreFile() }

    private fun write(storeFile: StoreFile) =
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(resolvedPath.toFile(), storeFile)
}
