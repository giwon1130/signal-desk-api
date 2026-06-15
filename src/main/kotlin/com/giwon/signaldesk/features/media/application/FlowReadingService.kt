package com.giwon.signaldesk.features.media.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * AI 시황 흐름 리딩 — 섹터 모멘텀·수급·순환매를 AI가 읽어 narrative로 만드는 "AI 리딩방(흐름형)".
 *
 * 소스는 유튜브가 아니라 시데가 이미 수집 중인 시장 데이터(섹터·수급·급등락·지수·뉴스)다.
 * 브리프와 동일한 [BriefPipeline] 골격을 재사용하고, 분석만 [GeminiClient.summarizeFlowReading]로,
 * 저장은 media_summaries(source=FLOW_READING)에 한다. v1은 피드만(푸시 없음).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class FlowReadingService(
    private val pipeline: BriefPipeline,
    private val geminiClient: GeminiClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    enum class Slot(val videoPrefix: String, val titleSuffix: String) {
        PREOPEN("flow-pre", "장전 시황 흐름"),
        CLOSE("flow-close", "마감 시황 흐름"),
    }

    /** 단일 실행. force=true 면 같은 날 기존 리딩이 있어도 재생성. */
    fun runFlow(slot: Slot, force: Boolean = false): MediaSummary? {
        val today = LocalDate.now(clock)
        // analyze 단계에서 실데이터로 추린 종목명을 캡처해 buildSummary 의 keyTickers 로 전달.
        var tickers: List<String> = emptyList()
        return pipeline.run(
            config = BriefPipeline.SlotConfig(
                logLabel = "FlowReading($slot)",
                videoPrefix = slot.videoPrefix,
                channelId = "ai-flow",
                channelTitle = "시데 AI 시황",
                titleSuffix = slot.titleSuffix,
                source = MediaSource.FLOW_READING,
            ),
            today = today,
            force = force,
            prepare = {},
            analyze = { _, d ->
                tickers = deriveTickers(d)
                geminiClient.summarizeFlowReading(
                    slot = slot.name,
                    vix = d.vix, indices = d.indices,
                    krMarket = d.krMarket, krGainers = d.krGainers, krLosers = d.krLosers,
                    investorFlow = d.investorFlow, headlines = d.headlines,
                )
            },
            transcriptLength = { _, d -> d.headlines.size },
            keyTickers = { tickers },
            dispatchPush = { _, _ -> },  // v1: 피드만, 푸시 없음
        )
    }

    /** 흐름 핵심 종목 — 급등 상위 + 외국인·기관 순매수 상위(실데이터, 환각 없음). 표시용 이름. */
    private fun deriveTickers(d: BriefPipeline.KrMarketData): List<String> {
        val gainers = d.krGainers.take(2).map { it.name }
        val foreign = d.investorFlow?.kospiForeignBuy?.take(2)?.map { it.name } ?: emptyList()
        val inst = d.investorFlow?.kospiInstitutionBuy?.take(2)?.map { it.name } ?: emptyList()
        return (gainers + foreign + inst).filter { it.isNotBlank() }.distinct().take(6)
    }
}
