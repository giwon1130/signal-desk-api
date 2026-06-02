package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.ai.application.PickCandidate
import com.giwon.signaldesk.features.events.application.MarketEvent
import com.giwon.signaldesk.features.market.application.InvestorFlowSnapshot
import com.giwon.signaldesk.features.market.application.InvestorRankItem
import com.giwon.signaldesk.features.market.application.MacroSnapshot
import com.giwon.signaldesk.features.market.application.MarketNews
import com.giwon.signaldesk.features.market.application.UsIndicesSnapshot
import com.giwon.signaldesk.features.market.application.VixSnapshot
import com.giwon.signaldesk.features.market.application.YahooQuote

/**
 * Gemini API 에 보내는 프롬프트 빌더.
 * 4종 시나리오(MarketInsight / MorningBrief / EveningBrief / NewsDigest / AiPicks) 의 프롬프트 텍스트만 담당.
 * HTTP 호출은 [GeminiClient], 응답 파싱은 [GeminiResponseParsing] 참조.
 */
internal object GeminiPrompts {

    fun marketInsight(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        headlines: List<MarketNews>,
        upcomingEvents: List<MarketEvent>,
    ): String {
        val capped = headlines.take(25)
        val headlineLines = capped.joinToString("\n") { n -> "- [${n.source}] ${n.title}" }
        val eventsBlock = if (upcomingEvents.isNotEmpty()) {
            val lines = upcomingEvents.take(8).joinToString("\n") { e ->
                val time = e.time?.let { " $it" } ?: ""
                "- [${e.date}$time · ${e.market}] ${e.title}${e.description?.let { " — $it" } ?: ""}"
            }
            "\n            === 다가오는 주요 이벤트 (3일내) ===\n            $lines"
        } else ""

        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            아래 시장 지표와 뉴스 헤드라인을 종합해 한국 개인 투자자를 위한 오늘의 종합 시장 인사이트를 작성하세요.
            모든 문장은 한국어 하십시오체(~습니다체)로 작성하세요.

            === 시장 지표 ===
            ${vixLine(vix)}
            ${nasdaqLine(indices)}
            ${sp500Line(indices)}
$eventsBlock
            === 오늘 주요 뉴스 헤드라인 (${capped.size}건) ===
            $headlineLines

            아래 JSON 스키마로 한국어 답변:
            {
              "headline": "오늘 시장을 한 줄로 압축 (20자 이내, 핵심 키워드 포함)",
              "summary": "2~3문장 종합 분석. VIX·지수·뉴스와 다가오는 이벤트(있다면)를 연결해 지금 시장 분위기와 개인 투자자 행동 포인트를 설명",
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나",
              "keyPoints": ["주목할 포인트 최대 3가지. 각 20자 이내. 다가오는 큰 이벤트가 있으면 1개는 그것에 할당"]
            }
        """.trimIndent()
    }

    fun morningBrief(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        macro: MacroSnapshot?,
        headlines: List<MarketNews>,
        disclosureTitles: List<String>,
        investorFlow: InvestorFlowSnapshot?,
        upcomingEvents: List<MarketEvent>,
    ): String {
        val capped = headlines.take(20)
        val headlineLines = capped.joinToString("\n") { n -> "- [${n.source}] ${n.title}" }
        val macroBlock = macroBlock(macro)
        val flowBlock = investorFlowBlock(investorFlow)
        val disclosureBlock = if (disclosureTitles.isNotEmpty()) {
            val lines = disclosureTitles.take(15).joinToString("\n") { "- $it" }
            "\n            === 사용자 보유/관심 종목의 어젯밤 ~ 오늘 아침 공시 (${disclosureTitles.size}건) ===\n            $lines"
        } else ""
        val eventsBlock = if (upcomingEvents.isNotEmpty()) {
            val lines = upcomingEvents.take(5).joinToString("\n") { e ->
                val time = e.time?.let { " $it" } ?: ""
                "- [${e.date}$time · ${e.market}] ${e.title}${e.description?.let { " — $it" } ?: ""}"
            }
            "\n            === 다가오는 주요 이벤트 (3일내) ===\n            $lines"
        } else ""

        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            지금은 한국 장 시작 30분 전(08:30 KST). 한국 개인 투자자가 '오늘 장을 어떻게 대응할지'
            준비할 수 있도록 야간 미국장 결과 + 한국 뉴스 + 보유/관심 종목 공시를 종합해 브리핑하세요.
            모든 문장은 한국어 하십시오체(~습니다체)로 작성하세요.

            === 야간 미국장 ===
            ${vixLine(vix)}
            ${nasdaqLine(indices)}
            ${sp500Line(indices)}
$macroBlock$flowBlock$disclosureBlock$eventsBlock
            === 오늘 한국·미국 시장 뉴스 헤드라인 (${capped.size}건) ===
            $headlineLines

            아래 JSON 스키마로 한국어 답변:
            {
              "headline": "오늘 장의 핵심을 한 줄로 (20자 이내, 매수/관망/방어 같은 액션 키워드 포함)",
              "summary": "3~4문장. 야간 미국장 → 한국장 영향 → 보유 종목 공시 → 오늘 대응 포인트 순으로 연결. 마지막 문장에 '오늘 행동' 명시",
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나",
              "keyPoints": ["오늘 봐야 할 포인트 3개. 각 25자 이내. 공시·이벤트 우선 반영"]
            }
        """.trimIndent()
    }

    /**
     * KR 장중/마감 브리프. slot = "MIDDAY"(12:30, 오전장 점검) | "CLOSE"(15:40, 마감 정리).
     * 모닝 브리프와 같은 입력(VIX/미국지수/매크로/헤드라인/수급/이벤트)을 KR 관점으로 재해석.
     */
    fun intradayBrief(
        slot: String,
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        macro: MacroSnapshot?,
        headlines: List<MarketNews>,
        investorFlow: InvestorFlowSnapshot?,
        upcomingEvents: List<MarketEvent>,
    ): String {
        val capped = headlines.take(20)
        val headlineLines = capped.joinToString("\n") { n -> "- [${n.source}] ${n.title}" }
        val macroBlock = macroBlock(macro)
        val flowBlock = investorFlowBlock(investorFlow)
        val eventsBlock = if (upcomingEvents.isNotEmpty()) {
            val lines = upcomingEvents.take(5).joinToString("\n") { e ->
                val time = e.time?.let { " $it" } ?: ""
                "- [${e.date}$time · ${e.market}] ${e.title}${e.description?.let { " — $it" } ?: ""}"
            }
            "\n            === 다가오는 주요 이벤트 (3일내) ===\n            $lines"
        } else ""

        val (situation, headlineHint, summaryHint, pointHint) = when (slot) {
            "MIDDAY" -> Quad(
                "지금은 한국 장중(12:30 KST), 오전장이 끝난 시점입니다. 한국 개인 투자자가 '오후장을 어떻게 대응할지' 판단하도록 오전장 흐름·수급·이슈를 중간 점검하세요.",
                "오전장 분위기를 한 줄로 (20자 이내, 강세/약세/관망 키워드 포함)",
                "3문장. 오전장 지수·수급 흐름 → 주도 이슈/뉴스 → 오후장 대응 포인트 순. 마지막 문장에 '오후 행동' 명시",
                "오후장 봐야 할 포인트 3개. 각 25자 이내. 수급·이벤트 우선",
            )
            else -> Quad(
                "지금은 한국 장 마감 직후(15:40 KST)입니다. 한국 개인 투자자가 '오늘 장을 정리하고 내일을 준비'하도록 마감 흐름·수급·이슈를 종합하세요.",
                "오늘 장 마감을 한 줄로 (20자 이내, 강세/약세/혼조 키워드 포함)",
                "3~4문장. 오늘 지수·수급 마감 → 주도 섹터/이슈 → 내일 관전 포인트 순. 마지막 문장에 '내일 관전 포인트' 명시",
                "내일 봐야 할 포인트 3개. 각 25자 이내. 수급·이벤트 우선",
            )
        }

        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            $situation
            모든 문장은 한국어 하십시오체(~습니다체)로 작성하세요.

            === 글로벌 매크로 컨텍스트 ===
            ${vixLine(vix)}
            ${nasdaqLine(indices)}
            ${sp500Line(indices)}
$macroBlock$flowBlock$eventsBlock
            === 오늘 한국·미국 시장 뉴스 헤드라인 (${capped.size}건) ===
            $headlineLines

            아래 JSON 스키마로 한국어 답변:
            {
              "headline": "$headlineHint",
              "summary": "$summaryHint",
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나",
              "keyPoints": ["$pointHint"]
            }
        """.trimIndent()
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    fun eveningBrief(
        vix: VixSnapshot?,
        indices: UsIndicesSnapshot?,
        topGainers: List<YahooQuote>,
        topLosers: List<YahooQuote>,
        earningsSymbols: List<String>,
        headlines: List<MarketNews>,
    ): String {
        val capped = headlines.filter { it.market == "US" }.take(15)
        val headlineLines = if (capped.isEmpty()) "(미국 뉴스 데이터 없음)"
            else capped.joinToString("\n") { n -> "- [${n.source}] ${n.title}" }
        val gainersBlock = if (topGainers.isNotEmpty()) {
            "\n            === 급등 Top (Yahoo most_actives gainers) ===\n            " +
                topGainers.take(5).joinToString("\n") { q -> "- ${q.ticker} (${q.name}) ${"%+.2f".format(q.changeRate)}%" }
        } else ""
        val losersBlock = if (topLosers.isNotEmpty()) {
            "\n            === 급락 Top ===\n            " +
                topLosers.take(5).joinToString("\n") { q -> "- ${q.ticker} (${q.name}) ${"%+.2f".format(q.changeRate)}%" }
        } else ""
        val earningsBlock = if (earningsSymbols.isNotEmpty()) {
            "\n            === 오늘 실적 발표 (${earningsSymbols.size}건) ===\n            " + earningsSymbols.take(15).joinToString(", ")
        } else ""

        return """
            당신은 미국 주식 시장 전문 분석가입니다.
            지금은 NY 장 마감 직후 (06:30 KST). 한국 개인 투자자가 '어제 미국장 어땠고 오늘 한국장에 어떤 영향 있을지'를 알 수 있게 이브닝 브리프를 작성하세요.
            모든 문장은 한국어 하십시오체(~습니다체)로 작성하세요.

            === 미국 시장 마감 ===
            ${vixLine(vix)}
            ${nasdaqLine(indices)}
            ${sp500Line(indices)}
$gainersBlock$losersBlock$earningsBlock
            === 미국 시장 뉴스 헤드라인 (${capped.size}건) ===
            $headlineLines

            아래 JSON 스키마로 한국어 답변:
            {
              "headline": "미국장 마감을 한 줄로 (20자 이내, 강세/약세/혼조 같은 키워드 포함)",
              "summary": "3~4문장. 지수 마감 → 주도주·급등락 → 실적 이슈 → 오늘 한국장 시사점 순으로 연결",
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나",
              "keyPoints": ["오늘 봐야 할 미국장 포인트 3개. 각 25자 이내"]
            }
        """.trimIndent()
    }

    fun newsDigest(
        marketLabel: String,
        dateLabel: String,
        headlines: List<Triple<String, String, String>>,
    ): String {
        // 헤드라인이 너무 많으면 토큰 폭증 → 최신 60건으로 제한.
        val capped = headlines.take(60)
        val lines = capped.joinToString("\n") { (source, title, _) -> "- [$source] $title" }
        val marketKo = if (marketLabel == "KR") "한국" else "미국"
        return """
            당신은 한국 주식 투자 전문 분석가입니다.
            아래는 $dateLabel 자 $marketKo 시장 관련 뉴스 헤드라인 묶음입니다 (${capped.size}건).
            여러 매체의 헤드라인을 종합해 한국 개인 투자자가 한 눈에 시황을 파악할 수 있도록
            아래 JSON 스키마에 맞춰 한국어로 답변하세요.
            모든 문장은 한국어 하십시오체(~습니다체)로 작성하세요.

            스키마:
            {
              "summary": "3~5문장으로 오늘 시장의 핵심 흐름 요약 (각 문장은 줄바꿈으로 구분)",
              "flowAnalysis": "2~3문장으로 시장 흐름 해석 — 강세/약세/관망의 이유와 주목할 섹터",
              "keyTickers": ["헤드라인에서 반복 언급된 종목명 또는 티커. 한국 종목은 6자리 코드(예: 005930), 미국 종목은 티커(예: NVDA). 최대 6개"],
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나"
            }

            헤드라인:
            $lines
        """.trimIndent()
    }

    /**
     * 급등/급락 종목별 사유 — 각 종목에 매칭된 최근 뉴스 헤드라인을 근거로
     * "왜 올랐나/내렸나" 를 한국어 한 문장으로 짧게 설명한다.
     */
    fun moverReasons(
        dateLabel: String,
        movers: List<MoverReasonInput>,
    ): String {
        val blocks = movers.joinToString("\n\n") { m ->
            val sign = if (m.changeRate >= 0) "+" else ""
            val newsLines = if (m.headlines.isEmpty()) {
                "  관련뉴스: (매칭된 헤드라인 없음)"
            } else {
                m.headlines.joinToString("\n") { "  - $it" }
            }
            "- [${m.market}] [${m.ticker}] ${m.name} (${sign}${"%.1f".format(m.changeRate)}%, ${m.direction})\n$newsLines"
        }
        return """
            당신은 한국 주식 시황 분석가입니다.
            아래는 $dateLabel 자 급등·급락한 종목들과, 각 종목에 매칭된 최근 뉴스 헤드라인입니다.
            각 종목이 왜 그렇게 움직였는지 한국 개인 투자자가 이해하기 쉽게 한국어 한 문장(40자 이내)으로 설명하세요.
            모든 문장은 한국어 하십시오체(~습니다체)로 작성하세요.

            규칙:
            - 매칭된 뉴스가 있으면 그 내용을 근거로 구체적으로 설명.
            - 매칭된 뉴스가 없으면 "뚜렷한 뉴스 없이 수급·차트 모멘텀으로 추정" 처럼 단정하지 말고 추정으로 표현.
            - 과장/투자권유 금지. 사실 위주로 담백하게.

            스키마:
            {
              "reasons": [
                { "ticker": "종목코드 또는 티커(입력 그대로)", "reason": "한 문장 사유(40자 이내)" }
              ]
            }

            종목:
            $blocks
        """.trimIndent()
    }

    fun aiPicks(
        candidates: List<PickCandidate>,
        headlines: List<MarketNews>,
    ): String {
        // [KR] / [US] 마켓 태그를 줄 앞에 노출해 Gemini 가 시장 구분 + ticker 포맷 (6자리 숫자 vs 영문) 을 정확히 따르게 한다.
        val candidateLines = candidates.take(60).joinToString("\n") { c ->
            val rate = c.changeRate?.let { " ${"%+.2f".format(it)}%" } ?: ""
            val flow = c.flowTag?.let { " [$it]" } ?: ""
            "- [${c.market}] ${c.name}(${c.ticker})$rate$flow"
        }
        val headlineLines = headlines.take(20).joinToString("\n") { "- [${it.source}] ${it.title}" }

        return """
            당신은 한국·미국 주식 단타 전문 분석가입니다.
            아래는 오늘 시장에서 움직임이 큰 종목 후보 목록입니다.
            - KR: 급등/급락 상위 + 외인·기관 순매수 상위 (ticker 는 6자리 숫자, 앞자리 0 포함)
            - US: Yahoo top gainers/losers (ticker 는 영문 심볼 — NVDA/TSLA 등)
            이 목록 안에서만 골라 오늘 단타 관점에서 주목할 종목 3~5개를 추천하세요.
            **목록에 없는 종목(ticker)은 절대 추천하지 마세요.**
            가급적 KR/US 가 섞이도록 다양성을 확보하되, 근거가 약한 종목은 빼고 강한 것만 골라도 됩니다.
            reason·riskNote·summary 등 모든 문장은 한국어 하십시오체(~습니다체)로 작성하세요.

            선정 원칙:
            - 이미 +15% 이상 급등한 종목은 추격매수 리스크가 크다. 단순 급등률만 보고 고르지 말 것.
              추천한다면 riskNote 에 추격 리스크를 명시.
            - [외인 순매수] / [기관 순매수] 태그가 붙은 종목(KR)을 우선 고려하라 —
              수급이 뒷받침돼야 모멘텀이 지속된다. 급등률보다 수급·뉴스 근거가 우선.
            - US 는 수급 태그가 없으므로 뉴스 헤드라인·섹터 흐름과 연결지어 근거를 만들 것.
            - confidence 는 보수적으로. 수급·뉴스 근거 없이 급등만으론 60 이하.

            === 종목 후보 (이 안에서만 선택) ===
            $candidateLines

            === 오늘 시장 뉴스 헤드라인 ===
            $headlineLines

            아래 JSON 스키마로 한국어 답변:
            {
              "summary": "오늘 픽 전반의 시황 한 줄 (30자 이내)",
              "picks": [
                {
                  "ticker": "후보 목록의 ticker 그대로 — KR 은 6자리 숫자(앞자리 0 포함, 예: \"017900\"), US 는 영문 심볼(예: \"NVDA\")",
                  "name": "후보 목록의 종목명 그대로",
                  "reason": "추천 근거 2~3문장 — 수급/모멘텀/뉴스 연결",
                  "expectedReturnRate": 5.0,
                  "confidence": 70,
                  "riskNote": "리스크 한 줄 (20자 이내)"
                }
              ]
            }
            confidence 는 0~100 정수, expectedReturnRate 는 % 숫자(3~20 권장).
        """.trimIndent()
    }

    // ─── 공통 헬퍼 ───────────────────────────────────────────────────────────
    private fun vixLine(vix: VixSnapshot?) =
        if (vix != null) "VIX(공포지수): ${vix.currentPrice} (변화: ${vix.priceChange})" else "VIX: 데이터 없음"

    private fun nasdaqLine(indices: UsIndicesSnapshot?) =
        if (indices?.nasdaq != null) "NASDAQ: ${indices.nasdaq.currentValue} (${indices.nasdaq.changeRate}%)"
        else "NASDAQ: 데이터 없음"

    private fun sp500Line(indices: UsIndicesSnapshot?) =
        if (indices?.sp500 != null) "S&P500: ${indices.sp500.currentValue} (${indices.sp500.changeRate}%)"
        else "S&P500: 데이터 없음"

    private fun investorFlowBlock(flow: InvestorFlowSnapshot?): String {
        if (flow == null || flow.isEmpty()) return ""
        fun line(label: String, items: List<InvestorRankItem>) =
            if (items.isEmpty()) null
            else "- $label: ${items.take(5).joinToString(", ") { "${it.name}(${it.ticker})" }}"
        val parts = listOfNotNull(
            line("KOSPI 외인 순매수", flow.kospiForeignBuy),
            line("KOSPI 외인 순매도", flow.kospiForeignSell),
            line("KOSPI 기관 순매수", flow.kospiInstitutionBuy),
            line("KOSPI 기관 순매도", flow.kospiInstitutionSell),
            line("KOSDAQ 외인 순매수", flow.kosdaqForeignBuy),
        )
        if (parts.isEmpty()) return ""
        return "\n            === 어제 수급 상위 ===\n            ${parts.joinToString("\n            ")}\n"
    }

    private fun macroBlock(macro: MacroSnapshot?): String {
        if (macro == null) return ""
        val lines = buildList {
            macro.cpi?.let { add("- CPI: ${"%.1f".format(it.currentValue)} (전월 대비 ${"%+.2f".format(it.changeRate)}%)") }
            macro.fedFundsRate?.let { add("- Fed Funds Rate: ${"%.2f".format(it.currentValue)}% (변화 ${"%+.2f".format(it.changeRate)}%p)") }
            macro.usdKrw?.let { add("- USD/KRW: ${"%.1f".format(it.currentValue)} (변화 ${"%+.2f".format(it.changeRate)}%)") }
            macro.treasury10y?.let { add("- 10년물 국채: ${"%.2f".format(it.currentValue)}% (${"%+.2f".format(it.changeRate)}%p)") }
            macro.wti?.let { add("- WTI 유가: ${"%.1f".format(it.currentValue)} (${"%+.2f".format(it.changeRate)}%)") }
        }
        if (lines.isEmpty()) return ""
        return "\n            === 매크로 지표 ===\n            ${lines.joinToString("\n            ")}\n"
    }
}
