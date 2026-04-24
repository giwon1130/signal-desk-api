package com.giwon.signaldesk.features.fortune.application

/**
 * 오늘의 투자 운세.
 *
 * "운세"이지만 실제 매매 근거로 쓰라는 의도는 아니고, 사용자에게 가볍게 한마디 던지는 카드.
 * 점수·문구는 (userId + 오늘 날짜)를 시드로 결정론적으로 생성되므로 같은 하루엔 같은 결과가 나온다.
 */
data class DailyFortune(
    val date: String,               // YYYY-MM-DD (KST 기준)
    val overallScore: Int,          // 0~100
    val overallLabel: String,       // 대길 / 길 / 평 / 주의 / 흉
    val overallTone: String,        // good / neutral / bad (앱 색상 결정용)
    val headline: String,           // 한 줄 총평
    val message: String,            // 2~3문장 해설
    val wealthScore: Int,           // 재물운
    val tradeScore: Int,            // 매매운
    val patienceScore: Int,         // 인내운
    val luckyHour: String,          // "14시 ~ 15시"
    val luckyColor: String,         // "코스모스 레드" 같은 분위기 있는 표현
    val luckyNumber: Int,           // 1~9
    val luckyTheme: String,         // 오늘 어울리는 투자 테마 (반도체 / 방어주 / 현금 보유 등)
    val caution: String,            // 오늘 조심할 것
    val mantra: String,             // 오늘의 한마디 (반말, 부적 같은 느낌)
    val disclaimer: String,         // 재미용 면책
)
