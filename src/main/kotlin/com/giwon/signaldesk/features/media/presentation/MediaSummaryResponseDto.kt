package com.giwon.signaldesk.features.media.presentation

import com.giwon.signaldesk.features.media.application.MediaSummary

data class MediaSummaryResponse(
    val id: String,
    val channelTitle: String,
    val videoTitle: String,
    val videoUrl: String,
    val publishedAt: String,
    val summary: String,
    val flowAnalysis: String,
    val keyTickers: List<String>,
    val sentiment: String,
    val hasTranscript: Boolean,
) {
    companion object {
        fun from(item: MediaSummary) = MediaSummaryResponse(
            id = item.id,
            channelTitle = item.channelTitle,
            videoTitle = item.videoTitle,
            videoUrl = item.videoUrl,
            publishedAt = item.publishedAt.toString(),
            summary = item.summary,
            flowAnalysis = item.flowAnalysis,
            keyTickers = item.keyTickers,
            sentiment = item.sentiment.name,
            hasTranscript = item.hasTranscript,
        )
    }
}
