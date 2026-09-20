package com.giwon.signaldesk.features.media.application

import com.giwon.signaldesk.features.ai.application.PickCandidate
import com.giwon.signaldesk.features.market.application.MarketNews

/**
 * Gemini API 에 보내는 프롬프트 빌더.
 * 외부 콘텐츠 요약·종목 후보 표현용. 시황 판단은 MarketEvidenceAnalyzer가 담당.
 * HTTP 호출은 [GeminiClient], 응답 파싱은 [GeminiResponseParsing] 참조.
 */
internal object GeminiPrompts {

    /** 유튜브 방송 자막을 시청자 대신 요약 — 방송이 본 시장 흐름·테마·종목 코멘트. */
    fun youtubeFlowSummary(channelLabel: String, videoTitle: String, transcript: String): String {
        return """
            당신은 한국 주식 방송을 시청자 대신 요약하는 애널리스트입니다.
            아래는 '$channelLabel' 방송 '$videoTitle'의 자막입니다(자동 생성이라 오탈자 가능).
            이 방송에서 다룬 시장 흐름·주요 테마·섹터/종목 코멘트를 핵심만 요약하세요.
            규칙: 자막에 실제로 나온 내용만 요약하고 없는 수치·종목은 지어내지 마세요.
            매수/매도 단정은 금지 — 방송의 '시각(뷰)'을 전달하되 참고용임을 전제합니다. 한국어 하십시오체.

            === 방송 자막 ===
            $transcript

            아래 JSON 스키마로 한국어 답변:
            {
              "headline": "방송 핵심을 한 줄로 (24자 이내)",
              "summary": "3~4문장. 방송이 본 시장 흐름·주요 테마·시각을 요약",
              "sentiment": "BULLISH | BEARISH | NEUTRAL 중 하나 (방송의 전반적 톤)",
              "keyPoints": ["방송에서 강조한 포인트 3~5개. 각 30자 이내. 언급된 섹터/종목/이벤트 위주"]
            }
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
            이 목록 안에서만 골라 오늘 검토할 후보를 최대 5개 제시하세요. 근거가 없으면 빈 배열을 반환하세요.
            **목록에 없는 종목(ticker)은 절대 추천하지 마세요.**
            가급적 KR/US 가 섞이도록 다양성을 확보하되, 근거가 약한 종목은 빼고 강한 것만 골라도 됩니다.
            reason·riskNote·summary는 자연스러운 한국어 반말(~해, ~봐)로 작성하세요.

            선정 원칙:
            - 이미 +15% 이상 급등한 종목은 추격매수 리스크가 크다. 단순 급등률만 보고 고르지 말 것.
              추천한다면 riskNote 에 추격 리스크를 명시.
            - [외인 순매수] / [기관 순매수] 태그가 붙은 종목(KR)을 우선 고려하라 —
              수급이 뒷받침돼야 모멘텀이 지속된다. 급등률보다 수급·뉴스 근거가 우선.
            - 뉴스와 종목의 연관성이 입력에 명확할 때만 연결하세요. 없는 실적·거래량·수급·미래 가격은 만들지 마세요.
            - confidence 는 보수적으로. 수급·뉴스 근거 없이 급등만으론 60 이하.
            - confidence 는 모델 의견이지 상승 확률이 아닙니다. 최종 검토 여부는 별도 데이터 규칙이 판단합니다.
            - expectedReturnRate 는 반드시 null로 반환하세요. 제공 데이터만으로 미래 수익률을 추정할 수 없습니다.

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
                  "expectedReturnRate": null,
                  "confidence": 70,
                  "riskNote": "리스크 한 줄 (20자 이내)"
                }
              ]
            }
            confidence 는 0~100 정수, expectedReturnRate 는 null.
        """.trimIndent()
    }

}
