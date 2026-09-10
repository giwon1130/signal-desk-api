package com.giwon.signaldesk.features.media.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.features.market.application.*
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate

class EvidenceBriefingIntegrationTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val input = MarketEvidenceInput(Instant.parse("2026-09-09T23:30:00Z"), emptyList(), null, null)
    private val report = MarketEvidenceAnalyzer(MarketSessionService()).analyze(input)

    @Test fun `fabricated Gemini output over HTTP is rejected and never changes analysis`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            val body = mapper.createObjectNode().apply {
                putArray("candidates").addObject().putObject("content").putArray("parts").addObject()
                    .put("text", """{"opening":"코스피 20% 상승 확정, 지금 매수"}""")
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val gemini = GeminiClient(ObjectMapper(), "test-only", "", "http://127.0.0.1:${server.address.port}", "test")
            val assessed = report.copy(regime = "MIXED") // exercise verbalizer even with a deliberately sparse fixture
            val result = EvidenceNarrator(gemini, mapper).narrate(assessed)
            assertThat(requests).isEqualTo(1)
            assertThat(result.summary).doesNotContain("20%", "지금 매수", "확정")
            assertThat(result.assessment).isEqualTo(assessed)
        } finally { server.stop(0) }
    }

    @Test fun `archive stores dated inputs and version without overwriting first observation`() {
        val jdbc = mock(JdbcTemplate::class.java)
        MarketEvidenceArchive(jdbc, mapper).save(input, report)
        val invocation = mockingDetails(jdbc).invocations.single()
        assertThat(invocation.arguments[0].toString()).contains("on conflict (bucket_at, rules_version) do nothing")
        assertThat(invocation.arguments.joinToString()).contains(MarketEvidenceAnalyzer.RULES_VERSION, "collectedAt", "INSUFFICIENT_DATA")
    }

    @Test fun `source failures and no Gemini key still produce a persisted scheduled brief`() {
        val yahoo = mock(YahooQuoteClient::class.java)
        val fred = mock(FredIndexClient::class.java)
        val news = mock(GoogleNewsRssClient::class.java)
        `when`(yahoo.fetchIndices(YahooQuoteClient.BRIEFING_INDICES)).thenThrow(IllegalStateException("offline"))
        `when`(fred.fetchMacro()).thenThrow(IllegalStateException("offline"))
        val provider = DefaultListableBeanFactory().getBeanProvider(MarketEvidenceArchive::class.java)
        val narrator = EvidenceNarrator(GeminiClient(ObjectMapper(), "", "", "http://unused", "test"), mapper)
        val briefing = EvidenceBriefingService(yahoo, fred, news, MarketEvidenceAnalyzer(MarketSessionService()), narrator, provider)
        val result = briefing.current()
        assertThat(result.assessment?.regime).isEqualTo("INSUFFICIENT_DATA")
        val repository = object : MediaSummaryRepository {
            var saved: MediaSummary? = null
            override fun findRecent(limit: Int) = emptyList<MediaSummary>()
            override fun findRecentBySource(source: MediaSource, limit: Int) = emptyList<MediaSummary>()
            override fun findById(id: String): MediaSummary? = null
            override fun findByVideoId(videoId: String) = saved
            override fun save(summary: MediaSummary): MediaSummary { saved = summary; return summary }
            override fun deleteByVideoId(videoId: String) { saved = null }
        }
        val pipeline = BriefPipeline(briefing, repository, mock(ExpoPushClient::class.java))
        var pushes = 0
        fun run() = pipeline.run(BriefPipeline.SlotConfig("test", "test", "test", "test", "test", MediaSource.MORNING_BRIEF),
            LocalDate.parse("2026-09-10"), false, prepare = {}, dispatchPush = { _, _ -> pushes++ })
        assertThat(run()?.summary).contains("최신 자료가 부족")
        assertThat(repository.saved?.flowAnalysis).contains("미연결")
        assertThat(run()).isNull()
        assertThat(pushes).isEqualTo(1)
    }

    @Test fun `ninety day retention includes evidence archive`() {
        val jdbc = mock(JdbcTemplate::class.java)
        com.giwon.signaldesk.features.maintenance.RetentionService(jdbc).runRetention()
        val call = mockingDetails(jdbc).invocations.single { it.arguments[0].toString().contains("signal_desk_market_evidence") }
        assertThat(call.arguments[1]).isEqualTo(90)
    }

    @Test fun `unconfigured or failing night feed cannot prevent the deterministic briefing`() {
        val provider = DefaultListableBeanFactory().getBeanProvider(MarketEvidenceArchive::class.java)
        val narrator = EvidenceNarrator(GeminiClient(ObjectMapper(), "", "", "http://unused", "test"), mapper)
        val service = EvidenceBriefingService(mock(YahooQuoteClient::class.java), mock(FredIndexClient::class.java),
            mock(GoogleNewsRssClient::class.java), MarketEvidenceAnalyzer(MarketSessionService()), narrator, provider,
            KrxNightFuturesFeed { throw IllegalStateException("test feed failure") })
        val result = service.current()
        assertThat(result.assessment?.evidence?.single { it.id == "KR_NIGHT" }?.status).isEqualTo("MISSING")
        assertThat(result.summary).contains("미연결")
    }

    @Test fun `validated night observation survives archive serialization and is not narrated as disconnected`() {
        val observed = Instant.parse("2026-09-09T21:00:00Z")
        val night = KrxNightFuturesObservation("TEST_CONTRACT", LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-09"),
            observed, observed, 404.0, 400.0, 1.0, KisNightFuturesDecoder.SOURCE)
        val withNight = input.copy(nightFutures = night)
        val decoded = mapper.readValue(mapper.writeValueAsString(withNight), MarketEvidenceInput::class.java)
        assertThat(decoded).isEqualTo(withNight)
        val assessed = MarketEvidenceAnalyzer(MarketSessionService()).analyze(decoded)
        val narrator = EvidenceNarrator(GeminiClient(ObjectMapper(), "", "", "http://unused", "test"), mapper)
        val result = narrator.narrate(assessed)
        assertThat(result.summary).contains("야간선물은 검증된 관측값만 반영")
        assertThat(result.summary).doesNotContain("야간선물 실측은 미연결")
    }
}
