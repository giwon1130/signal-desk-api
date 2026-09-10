package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.market.application.MarketEvidenceInput
import com.giwon.signaldesk.features.market.application.MarketEvidenceReport
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/** First snapshot per hour/version is immutable. No users, account data, secrets or full news bodies. */
@Repository
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MarketEvidenceArchive(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    /** Bounded, read-only archive access. Corrupt rows remain visible as rejected counts in the evaluator. */
    fun read(from: Instant, until: Instant): List<ArchivedMarketEvidence> = jdbc.query("""
        select bucket_at, rules_version, observed_at, inputs, analysis
        from signal_desk_market_evidence
        where observed_at >= ? and observed_at < ?
        order by observed_at desc, rules_version desc
        limit 2161
    """.trimIndent(), { rs, _ ->
        ArchivedMarketEvidence(rs.getTimestamp("bucket_at").toInstant(), rs.getString("rules_version"),
            rs.getTimestamp("observed_at").toInstant(),
            runCatching { mapper.readValue(rs.getString("inputs"), MarketEvidenceInput::class.java) }.getOrNull(),
            runCatching { mapper.readValue(rs.getString("analysis"), MarketEvidenceReport::class.java) }.getOrNull())
    }, Timestamp.from(from), Timestamp.from(until))

    fun save(input: MarketEvidenceInput, report: MarketEvidenceReport) {
        require(input.headlines.orEmpty().size <= 80) { "Archive input must be bounded before analysis" }
        jdbc.update("""
            insert into signal_desk_market_evidence (bucket_at, rules_version, observed_at, inputs, analysis)
            values (?, ?, ?, ?::jsonb, ?::jsonb)
            on conflict (bucket_at, rules_version) do nothing
        """.trimIndent(), Timestamp.from(input.collectedAt.truncatedTo(ChronoUnit.HOURS)), report.rulesVersion,
            Timestamp.from(input.collectedAt), mapper.writeValueAsString(input), mapper.writeValueAsString(report))
    }
}

data class ArchivedMarketEvidence(
    val bucketAt: Instant, val rulesVersion: String, val observedAt: Instant,
    val input: MarketEvidenceInput?, val report: MarketEvidenceReport?,
)
