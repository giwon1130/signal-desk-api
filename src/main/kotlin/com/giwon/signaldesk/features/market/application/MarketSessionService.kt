package com.giwon.signaldesk.features.market.application

import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Service
class MarketSessionService {

    fun buildMarketSessions(nowUtc: ZonedDateTime = ZonedDateTime.now(ZoneId.of("UTC"))): List<MarketSessionStatus> {
        val koreaNow = nowUtc.withZoneSameInstant(ZoneId.of("Asia/Seoul"))
        val usNow = nowUtc.withZoneSameInstant(ZoneId.of("America/New_York"))
        return listOf(
            resolveKrSession(koreaNow),
            resolveUsSession(usNow),
        )
    }

    private fun resolveKrSession(localNow: ZonedDateTime): MarketSessionStatus {
        val date = localNow.toLocalDate()
        val isWeekend = localNow.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val holiday = findKrHoliday(date)

        if (isWeekend) return closedSession("KR", "한국", localNow, "주말 휴장")
        if (holiday != null) return closedSession("KR", "한국", localNow, "${holiday.description} 휴장")

        return resolveSession(
            market = "KR",
            label = "한국",
            localNow = localNow,
            preStart = null,
            regularStart = LocalTime.of(9, 0),
            regularEnd = LocalTime.of(15, 30),
            afterEnd = null,
        )
    }

    fun buildMarketStatus(sessions: List<MarketSessionStatus>): String {
        val kr = sessions.firstOrNull { it.market == "KR" }
        val us = sessions.firstOrNull { it.market == "US" }
        return when {
            kr?.phase == "REGULAR" -> "KR_REGULAR_OPEN"
            us?.phase == "REGULAR" -> "US_REGULAR_OPEN"
            kr?.phase == "PRE_MARKET" || us?.phase == "PRE_MARKET" -> "PRE_MARKET"
            kr?.phase == "AFTER_HOURS" || us?.phase == "AFTER_HOURS" -> "AFTER_HOURS"
            else -> "MARKET_CLOSED"
        }
    }

    private fun resolveUsSession(localNow: ZonedDateTime): MarketSessionStatus {
        val date = localNow.toLocalDate()
        val time = localNow.toLocalTime()
        val isWeekend = localNow.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val holiday = findUsHoliday(date)
        val earlyClose = findUsEarlyClose(date)

        if (isWeekend) return closedSession("US", "미국", localNow, "주말 휴장")
        if (holiday != null) return closedSession("US", "미국", localNow, "${holiday.description} 휴장")

        val regularStart = LocalTime.of(9, 30)
        val regularEnd = earlyClose?.closeTime ?: LocalTime.of(16, 0)
        val preStart = LocalTime.of(4, 0)
        val afterEnd = if (earlyClose != null) null else LocalTime.of(20, 0)

        return when {
            time >= preStart && time < regularStart -> MarketSessionStatus(
                market = "US", label = "미국", phase = "PRE_MARKET", status = "장전", isOpen = false,
                localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                note = if (earlyClose != null) "${earlyClose.description} (정규장 13:00 조기종료)" else "프리마켓 진행 중",
            )
            time >= regularStart && time < regularEnd -> MarketSessionStatus(
                market = "US", label = "미국", phase = "REGULAR", status = "정규장", isOpen = true,
                localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                note = if (earlyClose != null) "${earlyClose.description} 조기종료일 정규장 진행 중" else "정규장 진행 중",
            )
            afterEnd != null && time >= regularEnd && time < afterEnd -> MarketSessionStatus(
                market = "US", label = "미국", phase = "AFTER_HOURS", status = "장후", isOpen = false,
                localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                note = "애프터마켓 진행 중",
            )
            else -> closedSession(
                market = "US", label = "미국", localNow = localNow,
                reason = if (earlyClose != null) "${earlyClose.description} 조기종료(13:00) 후 마감" else "정규장 종료",
            )
        }
    }

    private fun resolveSession(
        market: String,
        label: String,
        localNow: ZonedDateTime,
        preStart: LocalTime?,
        regularStart: LocalTime,
        regularEnd: LocalTime,
        afterEnd: LocalTime?,
    ): MarketSessionStatus {
        val isWeekday = localNow.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val time = localNow.toLocalTime()
        val phase: String
        val status: String
        val isOpen: Boolean
        val note: String

        when {
            !isWeekday -> { phase = "CLOSED"; status = "휴장"; isOpen = false; note = "주말 휴장" }
            preStart != null && time >= preStart && time < regularStart -> {
                phase = "PRE_MARKET"; status = "장전"; isOpen = false; note = "프리마켓 진행 중"
            }
            time >= regularStart && time < regularEnd -> {
                phase = "REGULAR"; status = "정규장"; isOpen = true; note = "정규장 진행 중"
            }
            afterEnd != null && time >= regularEnd && time < afterEnd -> {
                phase = "AFTER_HOURS"; status = "장후"; isOpen = false; note = "애프터마켓 진행 중"
            }
            else -> { phase = "CLOSED"; status = "마감"; isOpen = false; note = "정규장 종료" }
        }
        return MarketSessionStatus(
            market = market, label = label, phase = phase, status = status, isOpen = isOpen,
            localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
            note = note,
        )
    }

    private fun closedSession(market: String, label: String, localNow: ZonedDateTime, reason: String) =
        MarketSessionStatus(
            market = market, label = label, phase = "CLOSED",
            status = if (reason.contains("휴장")) "휴장" else "마감",
            isOpen = false,
            localTime = localNow.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
            note = reason,
        )

    /**
     * 한국 KRX 휴장일.
     *
     * 음력 기반 공휴일(설/추석/부처님오신날) + 대체휴일은 연도별로 변동돼서 자동 계산이 까다로움.
     * → KRX 공식 휴장일 캘린더(https://open.krx.co.kr) 기반으로 매년 수동 갱신.
     *
     * 갱신 주기: 매년 12월 KRX 가 다음 해 캘린더 공시할 때 추가.
     * 갱신 누락 시 효과: 음력 휴일에 시장이 "정규장"으로 잘못 표시됨 (시각만 영향, 실 데이터엔 무관).
     */
    private fun findKrHoliday(date: LocalDate): KrMarketSpecialDay? {
        val description = KR_HOLIDAYS_BY_DATE[date] ?: return null
        return KrMarketSpecialDay(date, description)
    }

    private fun findUsHoliday(date: LocalDate): UsMarketSpecialDay? {
        val year = date.year
        val holidays = setOf(
            observedDate(LocalDate.of(year, Month.JANUARY, 1)),
            nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3),
            nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3),
            easterSunday(year).minusDays(2),
            lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY),
            observedDate(LocalDate.of(year, Month.JUNE, 19)),
            observedDate(LocalDate.of(year, Month.JULY, 4)),
            firstWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY),
            nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4),
            observedDate(LocalDate.of(year, Month.DECEMBER, 25)),
        )
        return if (date in holidays) UsMarketSpecialDay(date, "미국 정규 휴장일") else null
    }

    private fun findUsEarlyClose(date: LocalDate): UsMarketEarlyClose? {
        val year = date.year
        val thanksgiving = nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)
        val earlyCloseDates = buildList {
            if (thanksgiving.plusDays(1).dayOfWeek == DayOfWeek.FRIDAY)
                add(UsMarketEarlyClose(thanksgiving.plusDays(1), "추수감사절 다음 날", LocalTime.of(13, 0)))
            val christmasEve = LocalDate.of(year, Month.DECEMBER, 24)
            if (christmasEve.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                add(UsMarketEarlyClose(christmasEve, "크리스마스 이브", LocalTime.of(13, 0)))
            val independenceEve = LocalDate.of(year, Month.JULY, 3)
            if (independenceEve.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && findUsHoliday(independenceEve) == null)
                add(UsMarketEarlyClose(independenceEve, "독립기념일 전일", LocalTime.of(13, 0)))
        }
        return earlyCloseDates.firstOrNull { it.date == date }
    }

    private fun observedDate(date: LocalDate) = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun firstWeekdayOfMonth(year: Int, month: Month, dayOfWeek: DayOfWeek) =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.firstInMonth(dayOfWeek))

    private fun nthWeekdayOfMonth(year: Int, month: Month, dayOfWeek: DayOfWeek, nth: Int) =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(nth, dayOfWeek))

    private fun lastWeekdayOfMonth(year: Int, month: Month, dayOfWeek: DayOfWeek) =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dayOfWeek))

    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        return LocalDate.of(year, (h + l - 7 * m + 114) / 31, ((h + l - 7 * m + 114) % 31) + 1)
    }

    data class UsMarketSpecialDay(val date: LocalDate, val description: String)
    data class UsMarketEarlyClose(val date: LocalDate, val description: String, val closeTime: LocalTime)
    data class KrMarketSpecialDay(val date: LocalDate, val description: String)

    companion object {
        // KRX 공식 휴장일 — 매년 12월 갱신.
        // 음력 기반(설/추석/부처님오신날) + 대체휴일 포함. 토/일과 겹치는 날은 따로 안 적음
        // (이미 주말 처리에서 잡힘).
        private val KR_HOLIDAYS_BY_DATE: Map<LocalDate, String> = buildMap {
            // 2026
            put(LocalDate.of(2026, 1, 1),  "신정")
            put(LocalDate.of(2026, 2, 16), "설날 연휴")
            put(LocalDate.of(2026, 2, 17), "설날")
            put(LocalDate.of(2026, 2, 18), "설날 연휴")
            put(LocalDate.of(2026, 3, 2),  "삼일절 대체휴일")  // 3/1 일요일
            put(LocalDate.of(2026, 5, 5),  "어린이날")
            put(LocalDate.of(2026, 5, 25), "부처님오신날 대체휴일")  // 5/24 일요일
            put(LocalDate.of(2026, 9, 24), "추석 연휴")
            put(LocalDate.of(2026, 9, 25), "추석")
            put(LocalDate.of(2026, 10, 5), "개천절 대체휴일")  // 10/3 토요일
            put(LocalDate.of(2026, 10, 9), "한글날")
            put(LocalDate.of(2026, 12, 25), "성탄절")
            put(LocalDate.of(2026, 12, 31), "연말 폐장")  // 한 해 마지막 영업일은 KRX 휴장

            // 2027 — 음력 휴일은 추후 KRX 공시 후 보정.
            put(LocalDate.of(2027, 1, 1),  "신정")
            put(LocalDate.of(2027, 3, 1),  "삼일절")
            put(LocalDate.of(2027, 5, 5),  "어린이날")
            put(LocalDate.of(2027, 6, 6),  "현충일")
            put(LocalDate.of(2027, 8, 16), "광복절 대체휴일")  // 8/15 일요일
            put(LocalDate.of(2027, 10, 4), "개천절 대체휴일")  // 10/3 일요일
            put(LocalDate.of(2027, 10, 11), "한글날 대체휴일")  // 10/9 토요일
            put(LocalDate.of(2027, 12, 31), "연말 폐장")
        }
    }
}
