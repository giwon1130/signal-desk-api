package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.market.application.MarketEvidenceInput
import com.giwon.signaldesk.features.market.application.MarketEvidenceReport
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

/** First snapshot per hour/version is immutable. No users, account data, secrets or full news bodies. */
@Repository
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MarketEvidenceArchive(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
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
