package com.giwon.signaldesk.features.market.application

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Trading day(거래일/휴장일) 안내 메시지 빌드.
 * MarketOverviewService에서 분리 — UI 안내 문구만.
 */
object MarketTradingDayBuilder {

    fun build(sessions: List<MarketSessionStatus>): TradingDayStatus {
        val kr = sessions.firstOrNull { it.market == "KR" }
        val us = sessions.firstOrNull { it.market == "US" }
        val krOpen = kr?.isOpen == true
        val usOpen = us?.isOpen == true
        // Today/브리프는 한국 투자자의 당일 행동을 안내한다. 한국과 미국은 시차 때문에
        // 같은 순간에 서로 다른 날짜일 수 있으므로, 미국장이 아직 금요일인 한국 토요일에도
        // 한국장 주말/휴장 상태가 흐려지지 않게 KR 세션을 기준으로 잡는다.
        val isWeekend = kr?.note?.contains("주말") == true
        val isHoliday = !isWeekend && kr?.note?.contains("휴장") == true

        val today = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val nextKrOpen = nextKoreanOpen(today)
        val nextLabel = "${koreanDayLabel(nextKrOpen.dayOfWeek)} ${nextKrOpen.toLocalDate()} 09:00 KST"

        val (headline, advice) = when {
            krOpen -> "한국장이 열려 있습니다" to "오늘의 단타 픽 / 보유 모니터를 그대로 사용하셔도 됩니다."
            isWeekend && usOpen -> "한국 주말 휴장 · 미국장 진행" to "한국 신규 진입은 다음 개장 후입니다. 미국 보유 종목만 점검해 주세요."
            isHoliday && usOpen -> "한국 휴장일 · 미국장 진행" to "한국 체결은 없으니 미국 보유 종목과 다음 한국장 시나리오만 확인해 주세요."
            isWeekend -> "한국 주말 휴장 — 다음 거래일 준비 모드" to "신규 진입은 다음 개장 후입니다. 오늘은 관심종목 정리, AI 로그 복기, 손절·익절 라인 재설정만 해 주세요."
            isHoliday -> "한국 휴장일 — 시장 재개 전 정리" to "한국 체결은 없으니 시나리오만 점검하고 다음 거래일을 준비해 주세요."
            usOpen -> "한국장 마감 · 미국장 진행" to "한국 신규 진입은 다음 개장 후입니다. 미국 보유 종목과 시장 흐름을 확인해 주세요."
            else -> "정규장 종료 — 시간외/다음날 준비" to "오늘 마감 결과를 보고 내일 진입 후보 1~2개만 추려두세요."
        }

        return TradingDayStatus(
            krOpen = krOpen, usOpen = usOpen,
            isWeekend = isWeekend, isHoliday = isHoliday,
            headline = headline, nextTradingDay = nextLabel, advice = advice,
        )
    }

    private fun nextKoreanOpen(now: ZonedDateTime): ZonedDateTime {
        var candidate = now.toLocalDate()
        if (now.toLocalTime() >= LocalTime.of(9, 0)) candidate = candidate.plusDays(1)
        while (candidate.dayOfWeek == DayOfWeek.SATURDAY || candidate.dayOfWeek == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1)
        }
        return candidate.atTime(9, 0).atZone(ZoneId.of("Asia/Seoul"))
    }

    private fun koreanDayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "월요일"
        DayOfWeek.TUESDAY -> "화요일"
        DayOfWeek.WEDNESDAY -> "수요일"
        DayOfWeek.THURSDAY -> "목요일"
        DayOfWeek.FRIDAY -> "금요일"
        DayOfWeek.SATURDAY -> "토요일"
        DayOfWeek.SUNDAY -> "일요일"
    }
}
