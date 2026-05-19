package com.giwon.signaldesk.features.media.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcMediaSummaryRepository(
    private val jdbcTemplate: JdbcTemplate,
) : MediaSummaryRepository {

    override fun findRecent(limit: Int): List<MediaSummary> =
        jdbcTemplate.query(
            "select * from signal_desk_media_summaries order by published_at desc limit ?",
            rowMapper, limit,
        )

    override fun findById(id: String): MediaSummary? =
        jdbcTemplate.query(
            "select * from signal_desk_media_summaries where id = ?",
            rowMapper, id,
        ).firstOrNull()

    override fun findByVideoId(videoId: String): MediaSummary? =
        jdbcTemplate.query(
            "select * from signal_desk_media_summaries where video_id = ?",
            rowMapper, videoId,
        ).firstOrNull()

    override fun save(summary: MediaSummary): MediaSummary {
        jdbcTemplate.update(
            """
            insert into signal_desk_media_summaries
                (id, channel_id, channel_title, video_id, video_title, video_url, published_at,
                 transcript_length, summary, flow_analysis, key_tickers, sentiment, has_transcript, source, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (video_id) do update set
                summary = excluded.summary,
                flow_analysis = excluded.flow_analysis,
                key_tickers = excluded.key_tickers,
                sentiment = excluded.sentiment,
                has_transcript = excluded.has_transcript,
                transcript_length = excluded.transcript_length,
                channel_title = excluded.channel_title,
                video_title = excluded.video_title,
                published_at = excluded.published_at
            """.trimIndent(),
            summary.id, summary.channelId, summary.channelTitle, summary.videoId,
            summary.videoTitle, summary.videoUrl, Timestamp.from(summary.publishedAt),
            summary.transcriptLength, summary.summary, summary.flowAnalysis,
            summary.keyTickers.joinToString(","), summary.sentiment.name, summary.hasTranscript,
            summary.source.name, Timestamp.from(summary.createdAt),
        )
        return summary
    }
}

private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
    MediaSummary(
        id = rs.getString("id"),
        channelId = rs.getString("channel_id"),
        channelTitle = rs.getString("channel_title"),
        videoId = rs.getString("video_id"),
        videoTitle = rs.getString("video_title"),
        videoUrl = rs.getString("video_url"),
        publishedAt = rs.getTimestamp("published_at").toInstant(),
        transcriptLength = rs.getInt("transcript_length"),
        summary = rs.getString("summary"),
        flowAnalysis = rs.getString("flow_analysis"),
        keyTickers = rs.getString("key_tickers").split(",").map { it.trim() }.filter { it.isNotBlank() },
        sentiment = runCatching { MediaSentiment.valueOf(rs.getString("sentiment")) }.getOrDefault(MediaSentiment.NEUTRAL),
        hasTranscript = rs.getBoolean("has_transcript"),
        source = runCatching { MediaSource.valueOf(rs.getString("source")) }.getOrDefault(MediaSource.NEWS_DIGEST),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
