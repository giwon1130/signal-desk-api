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

/**
 * 파일 기반 저장소(개발 모드 fallback).
 *
 * 주의: 단순화를 위해 user_id 스코핑을 무시한다 — 모든 사용자가 동일한 파일을 공유한다.
 * 사용자별 격리가 필요하면 jdbc 모드를 사용한다.
 */
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

    override fun loadWatchlist(userId: UUID?): List<WorkspaceWatchItem> = synchronized(lock) { read().watchlist }

    override fun saveWatchItem(userId: UUID?, item: WorkspaceWatchItem): WorkspaceWatchItem = synchronized(lock) {
        val next = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(watchlist = read().watchlist.filterNot { it.id == next.id }.plus(next).sortedBy { it.name }))
        next
    }

    override fun deleteWatchItem(userId: UUID?, id: String) = synchronized(lock) {
        write(read().copy(watchlist = read().watchlist.filterNot { it.id == id }))
    }

    override fun loadPortfolioPositions(userId: UUID?): List<WorkspaceHoldingPosition> = synchronized(lock) { read().portfolioPositions }

    override fun savePortfolioPosition(userId: UUID?, position: WorkspaceHoldingPosition): WorkspaceHoldingPosition = synchronized(lock) {
        val next = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(portfolioPositions = read().portfolioPositions.filterNot { it.id == next.id }.plus(next).sortedBy { it.name }))
        next
    }

    override fun deletePortfolioPosition(userId: UUID?, id: String) = synchronized(lock) {
        write(read().copy(portfolioPositions = read().portfolioPositions.filterNot { it.id == id }))
    }

    override fun loadPaperPositions(userId: UUID?): List<WorkspacePaperPosition> = synchronized(lock) { read().paperPositions }

    override fun savePaperPosition(userId: UUID?, position: WorkspacePaperPosition): WorkspacePaperPosition = synchronized(lock) {
        val next = position.copy(id = position.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(paperPositions = read().paperPositions.filterNot { it.id == next.id }.plus(next).sortedBy { it.name }))
        next
    }

    override fun deletePaperPosition(userId: UUID?, id: String) = synchronized(lock) {
        write(read().copy(paperPositions = read().paperPositions.filterNot { it.id == id }))
    }

    override fun loadPaperTrades(userId: UUID?): List<WorkspacePaperTrade> = synchronized(lock) { read().paperTrades }

    override fun savePaperTrade(userId: UUID?, trade: WorkspacePaperTrade): WorkspacePaperTrade = synchronized(lock) {
        val next = trade.copy(id = trade.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(paperTrades = read().paperTrades.filterNot { it.id == next.id }.plus(next).sortedByDescending { it.tradeDate }))
        next
    }

    override fun deletePaperTrade(userId: UUID?, id: String) = synchronized(lock) {
        write(read().copy(paperTrades = read().paperTrades.filterNot { it.id == id }))
    }

    override fun loadAiPicks(userId: UUID?): List<WorkspaceAiPick> = synchronized(lock) { read().aiPicks }

    override fun saveAiPick(userId: UUID?, pick: WorkspaceAiPick): WorkspaceAiPick = synchronized(lock) {
        val next = pick.copy(id = pick.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(aiPicks = read().aiPicks.filterNot { it.id == next.id }.plus(next).sortedByDescending { it.confidence }))
        next
    }

    override fun deleteAiPick(userId: UUID?, id: String) = synchronized(lock) {
        write(read().copy(aiPicks = read().aiPicks.filterNot { it.id == id }))
    }

    override fun loadAiTrackRecords(userId: UUID?): List<WorkspaceAiTrackRecord> = synchronized(lock) { read().aiTrackRecords }

    override fun saveAiTrackRecord(userId: UUID?, record: WorkspaceAiTrackRecord): WorkspaceAiTrackRecord = synchronized(lock) {
        val next = record.copy(id = record.id.ifBlank { UUID.randomUUID().toString() })
        write(read().copy(aiTrackRecords = read().aiTrackRecords.filterNot { it.id == next.id }.plus(next).sortedByDescending { it.recommendedDate }))
        next
    }

    override fun deleteAiTrackRecord(userId: UUID?, id: String) = synchronized(lock) {
        write(read().copy(aiTrackRecords = read().aiTrackRecords.filterNot { it.id == id }))
    }

    private fun read(): StoreFile =
        if (!Files.exists(resolvedPath)) StoreFile()
        else runCatching { objectMapper.readValue<StoreFile>(resolvedPath.toFile()) }.getOrElse { StoreFile() }

    private fun write(storeFile: StoreFile) =
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(resolvedPath.toFile(), storeFile)
}
