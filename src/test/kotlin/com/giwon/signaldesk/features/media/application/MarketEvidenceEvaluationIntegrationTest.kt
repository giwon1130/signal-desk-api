package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.bootstrap.ForbiddenException
import com.giwon.signaldesk.bootstrap.ValidationExceptionHandler
import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.auth.application.AuthException
import com.giwon.signaldesk.features.market.application.*
import com.giwon.signaldesk.features.media.presentation.MarketEvidenceEvaluationController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.*
import java.util.UUID

class MarketEvidenceEvaluationIntegrationTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val now = Instant.parse("2026-09-10T08:00:00Z")
    private val sessions = MarketSessionService()
    private val analyzer = MarketEvidenceAnalyzer(sessions)

    @Test fun `archive read is bounded dated and malformed JSON is retained as rejected row`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val archive = MarketEvidenceArchive(jdbc, mapper)
        archive.read(now.minusSeconds(86400), now)
        val call = mockingDetails(jdbc).invocations.single()
        assertThat(call.arguments[0].toString()).contains("observed_at >= ? and observed_at < ?", "limit 2161")
        @Suppress("UNCHECKED_CAST")
        val rowMapper = call.arguments[1] as RowMapper<ArchivedMarketEvidence>
        val rs = mock(ResultSet::class.java)
        `when`(rs.getTimestamp("bucket_at")).thenReturn(Timestamp.from(now))
        `when`(rs.getTimestamp("observed_at")).thenReturn(Timestamp.from(now))
        `when`(rs.getString("rules_version")).thenReturn("old")
        `when`(rs.getString("inputs")).thenReturn("{broken")
        `when`(rs.getString("analysis")).thenReturn("{}")
        val row = rowMapper.mapRow(rs, 0)!!
        assertThat(row.input).isNull()
        assertThat(row.report).isNull()
    }

    @Test fun `empty archive skips external data calls and feed failure degrades to unavailable outcomes`() {
        val archive = mock(MarketEvidenceArchive::class.java)
        val charts = mock(NaverIndexChartClient::class.java)
        val from = now.minus(Duration.ofDays(30))
        `when`(archive.read(from, now)).thenReturn(emptyList())
        val service = MarketEvidenceEvaluationService(archive, analyzer, sessions, charts, Clock.fixed(now, ZoneOffset.UTC))
        assertThat(service.evaluate(30).replay.snapshotCount).isZero()
        verifyNoInteractions(charts)
        val input = MarketEvidenceInput(now.minusSeconds(3600), emptyList(), null, null)
        val report = analyzer.analyze(input)
        `when`(archive.read(from, now)).thenReturn(listOf(ArchivedMarketEvidence(input.collectedAt, report.rulesVersion, input.collectedAt, input, report)))
        `when`(charts.fetchOhlc("KOSPI", NaverIndexChartClient.PeriodType.DAILY, 100)).thenThrow(IllegalStateException("offline"))
        assertThat(service.evaluate(30).replay.snapshotCount).isEqualTo(1)
    }

    @Test fun `admin authorization and day range are checked before any evaluation`() {
        val service = mock(MarketEvidenceEvaluationService::class.java)
        val auth = mock(AuthContext::class.java)
        val guard = mock(AdminGuard::class.java)
        val user = UUID.randomUUID()
        val admin = UUID.randomUUID()
        `when`(auth.requireUserId(null)).thenThrow(AuthException("login"))
        `when`(auth.requireUserId("Bearer user")).thenReturn(user)
        `when`(auth.requireUserId("Bearer admin")).thenReturn(admin)
        doThrow(ForbiddenException()).`when`(guard).requireAdmin(user)
        val mvc = MockMvcBuilders.standaloneSetup(MarketEvidenceEvaluationController(service, auth, guard))
            .setControllerAdvice(ValidationExceptionHandler()).build()
        mvc.get("/api/v1/insights/evaluation").andExpect { status { isUnauthorized() } }
        mvc.get("/api/v1/insights/evaluation") { header("Authorization", "Bearer user") }.andExpect { status { isForbidden() } }
        mvc.get("/api/v1/insights/evaluation?days=91") { header("Authorization", "Bearer admin") }.andExpect { status { isBadRequest() } }
        verifyNoInteractions(service)
        val report = MarketEvidenceEvaluator(analyzer, sessions).evaluate(emptyList(), emptyList(), now.minusSeconds(86400), now)
        `when`(service.evaluate(30)).thenReturn(report)
        mvc.get("/api/v1/insights/evaluation") { header("Authorization", "Bearer admin") }.andExpect {
            status { isOk() }
            jsonPath("$.data.calibrationStatus") { value("NOT_CALIBRATED") }
            jsonPath("$.data.inputs") { doesNotExist() }
        }
    }
}
