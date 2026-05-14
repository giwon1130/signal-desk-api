package com.giwon.signaldesk.features.media.application

interface MediaSummaryRepository {
    fun findRecent(limit: Int): List<MediaSummary>
    fun findById(id: String): MediaSummary?
    fun findByVideoId(videoId: String): MediaSummary?
    fun save(summary: MediaSummary): MediaSummary
}
