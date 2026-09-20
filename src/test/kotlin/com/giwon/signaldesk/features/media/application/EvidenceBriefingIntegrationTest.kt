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

    @Test fun `brief push is short action oriented and omits internal scoring language`() {
        val pipeline = BriefPipeline(mock(EvidenceBriefingService::class.java), mock(MediaSummaryRepository::class.java), mock(ExpoPushClient::class.java))
        val pressured = report.copy(
            regime = "PRESSURED",
            factors = listOf(EvidenceFactor(
                id = "semiconductors", label = "반도체 동행 지표", weight = .2, score = -.8,
                coverage = 1.0, evidenceIds = listOf("SOXX"), interpretation = "주식시장에 부담을 주는 흐름을 보이고 있습니다.",
            )),
        )
        val content = pipeline.briefPushContent(
            MarketInsightAnalysis("시장에 부담을 주는 흐름이 우세합니다", "긴 앱 본문", MediaSentiment.BEARISH, emptyList(), pressured),
            "마감 브리프",
        )
        assertThat(content.first).contains("시장 부담이 커지고 있습니다")
        assertThat(content.second).contains("반도체 관련 지표가 약세입니다", "확인해 주세요")
        assertThat(content.second).doesNotContain("유효 입력", "관측", "점수")
        assertThat(content.second.length).isLessThanOrEqualTo(180)
    }

    @Test fun `morning push uses the same action style and keeps disclosure context`() {
        val pipeline = BriefPipeline(mock(EvidenceBriefingService::class.java), mock(MediaSummaryRepository::class.java), mock(ExpoPushClient::class.java))
        val supportive = report.copy(
            regime = "SUPPORTIVE",
            factors = listOf(EvidenceFactor(
                id = "kr_night", label = "코스피200 야간선물", weight = .2, score = .8,
                coverage = 1.0, evidenceIds = listOf("KR_NIGHT"), interpretation = "국내 증시에 우호적인 흐름입니다.",
            )),
        )
        val content = pipeline.briefPushContent(
            MarketInsightAnalysis("내부 분석형 제목", "긴 앱 본문", MediaSentiment.BULLISH, emptyList(), supportive),
            fallbackTitle = "오늘의 모닝 브리프",
            leadingContext = "보유종목 공시 1건(삼성전자)도 확인해 주세요.",
        )
        assertThat(content.first).contains("우호적인 흐름이 나타나고 있습니다").doesNotContain("내부 분석형 제목")
        assertThat(content.second).startsWith("보유종목 공시 1건(삼성전자)도 확인해 주세요.")
        assertThat(content.second).contains("코스피200 야간선물이 강세입니다", "확인한 뒤 대응해 주세요")
        assertThat(content.second).doesNotContain("점수", "유효 입력", "관측")
        assertThat(content.second.length).isLessThanOrEqualTo(180)
    }

    @Test fun `unconfigured or failing night feed cannot prevent the deterministic briefing`() {
        val provider = DefaultListableBeanFactory().getBeanProvider(MarketEvidenceArchive::class.java)
        val narrator = EvidenceNarrator(GeminiClient(ObjectMapper(), "", "", "http://unused", "test"), mapper)
        val service = EvidenceBriefingService(mock(YahooQuoteClient::class.java), mock(FredIndexClient::class.java),
            mock(GoogleNewsRssClient::class.java), MarketEvidenceAnalyzer(MarketSessionService()), narrator, provider,
            KrxNightFuturesFeed { throw IllegalStateException("test feed failure") })
        val result = service.current()
        assertThat(result.assessment?.evidence?.single { it.id == "KR_NIGHT" }?.status).isEqualTo("MISSING")
        assertThat(result.summary).doesNotContain("미연결", "유효 입력", "관측 시각")
        assertThat(result.keyPoints.joinToString()).contains("미연결")
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
        assertThat(result.summary).doesNotContain("야간선물 실측", "관측값만 반영")
        assertThat(result.keyPoints.joinToString()).doesNotContain("야간선물 실측이 연결되지 않았거나")
    }
}
