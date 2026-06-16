package com.giwon.signaldesk.features.media.application

interface MediaSummaryRepository {
    fun findRecent(limit: Int): List<MediaSummary>
    fun findRecentBySource(source: MediaSource, limit: Int): List<MediaSummary>
    fun findById(id: String): MediaSummary?
    fun findByVideoId(videoId: String): MediaSummary?
    fun save(summary: MediaSummary): MediaSummary
    /** 발행 실패 시 중복방지 원장 롤백용 — 다음 스케줄에서 재시도되게. */
    fun deleteByVideoId(videoId: String)
}
