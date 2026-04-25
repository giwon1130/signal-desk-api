package com.giwon.signaldesk.features.market.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime

class MarketSessionServiceTest {

    private val service = MarketSessionService()

    // ── 한국 시장 ──────────────────────────────────────────────────────

    @Test
    fun `한국 정규장 시간대 09시 30분 - REGULAR`() {
        val sessions = service.buildMarketSessions(koreaNow(9, 30))
        val kr = sessions.first { it.market == "KR" }
        assertEquals("REGULAR", kr.phase)
        assertTrue(kr.isOpen)
    }

    @Test
    fun `한국 장전 08시 - CLOSED`() {
        val sessions = service.buildMarketSessions(koreaNow(8, 0))
        val kr = sessions.first { it.market == "KR" }
        assertEquals("CLOSED", kr.phase)
        assertFalse(kr.isOpen)
    }

    @Test
    fun `한국 장마감 15시 30분 이후 - CLOSED`() {
        val sessions = service.buildMarketSessions(koreaNow(15, 31))
        val kr = sessions.first { it.market == "KR" }
        assertEquals("CLOSED", kr.phase)
        assertFalse(kr.isOpen)
    }

    @Test
    fun `한국 주말 토요일 - CLOSED`() {
        val saturdayUtc = ZonedDateTime.of(2025, 4, 12, 1, 0, 0, 0, ZoneId.of("UTC")) // 토요일 한국 10시
        val sessions = service.buildMarketSessions(saturdayUtc)
        val kr = sessions.first { it.market == "KR" }
        assertEquals("CLOSED", kr.phase)
        assertEquals("주말 휴장", kr.note)
    }

    @Test
    fun `한국 설날 2026-02-17 화요일 - CLOSED 휴장`() {
        // 평일이지만 KRX 휴장. 정규장 시간대(11시)에 호출.
        val koreaUtc = ZonedDateTime.of(2026, 2, 17, 2, 0, 0, 0, ZoneId.of("UTC"))
        val sessions = service.buildMarketSessions(koreaUtc)
        val kr = sessions.first { it.market == "KR" }
        assertEquals("CLOSED", kr.phase)
        assertTrue(kr.note.contains("설날"))
    }

    @Test
    fun `한국 어린이날 2026-05-05 화요일 - CLOSED 휴장`() {
        val koreaUtc = ZonedDateTime.of(2026, 5, 5, 2, 0, 0, 0, ZoneId.of("UTC"))
        val sessions = service.buildMarketSessions(koreaUtc)
        val kr = sessions.first { it.market == "KR" }
        assertEquals("CLOSED", kr.phase)
        assertTrue(kr.note.contains("어린이날"))
    }

    @Test
    fun `한국 연말폐장 2026-12-31 목요일 - CLOSED 휴장`() {
        val koreaUtc = ZonedDateTime.of(2026, 12, 31, 2, 0, 0, 0, ZoneId.of("UTC"))
        val sessions = service.buildMarketSessions(koreaUtc)
        val kr = sessions.first { it.market == "KR" }
        assertEquals("CLOSED", kr.phase)
        assertTrue(kr.note.contains("연말"))
    }

    // ── 미국 시장 ──────────────────────────────────────────────────────

    @Test
    fun `미국 정규장 10시 - REGULAR`() {
        val sessions = service.buildMarketSessions(usNow(2025, 4, 14, 10, 0)) // 월요일
        val us = sessions.first { it.market == "US" }
        assertEquals("REGULAR", us.phase)
        assertTrue(us.isOpen)
    }

    @Test
    fun `미국 프리마켓 06시 - PRE_MARKET`() {
        val sessions = service.buildMarketSessions(usNow(2025, 4, 14, 6, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("PRE_MARKET", us.phase)
        assertFalse(us.isOpen)
    }

    @Test
    fun `미국 애프터마켓 17시 - AFTER_HOURS`() {
        val sessions = service.buildMarketSessions(usNow(2025, 4, 14, 17, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("AFTER_HOURS", us.phase)
        assertFalse(us.isOpen)
    }

    @Test
    fun `미국 주말 일요일 - CLOSED 주말 휴장`() {
        val sessions = service.buildMarketSessions(usNow(2025, 4, 13, 12, 0)) // 일요일
        val us = sessions.first { it.market == "US" }
        assertEquals("CLOSED", us.phase)
        assertEquals("주말 휴장", us.note)
    }

    @Test
    fun `미국 독립기념일 7월 4일 금요일 2025 - CLOSED`() {
        val sessions = service.buildMarketSessions(usNow(2025, 7, 4, 12, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("CLOSED", us.phase)
        assertTrue(us.note.contains("휴장"))
    }

    @Test
    fun `미국 추수감사절 2025년 11월 27일 목요일 - CLOSED`() {
        val sessions = service.buildMarketSessions(usNow(2025, 11, 27, 12, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("CLOSED", us.phase)
    }

    @Test
    fun `미국 블랙프라이데이 2025년 11월 28일 - CLOSED 13시 조기종료`() {
        val sessions = service.buildMarketSessions(usNow(2025, 11, 28, 14, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("CLOSED", us.phase)
        assertTrue(us.note.contains("조기종료"))
    }

    @Test
    fun `미국 크리스마스 이브 2025년 12월 24일 수요일 오전 중 - REGULAR 조기종료일`() {
        val sessions = service.buildMarketSessions(usNow(2025, 12, 24, 11, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("REGULAR", us.phase)
        assertTrue(us.note.contains("조기종료"))
    }

    @Test
    fun `미국 크리스마스 이브 2025년 12월 24일 오후 13시 이후 - CLOSED`() {
        val sessions = service.buildMarketSessions(usNow(2025, 12, 24, 14, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("CLOSED", us.phase)
    }

    // ── buildMarketStatus ──────────────────────────────────────────────

    @Test
    fun `buildMarketStatus 한국 정규장 진행 중 - KR_REGULAR_OPEN`() {
        val sessions = service.buildMarketSessions(koreaNow(10, 0))
        assertEquals("KR_REGULAR_OPEN", service.buildMarketStatus(sessions))
    }

    @Test
    fun `buildMarketStatus 미국 정규장만 진행 중 - US_REGULAR_OPEN`() {
        val sessions = service.buildMarketSessions(usNow(2025, 4, 14, 11, 0))
        assertEquals("US_REGULAR_OPEN", service.buildMarketStatus(sessions))
    }

    @Test
    fun `buildMarketStatus 프리마켓 구간 - PRE_MARKET`() {
        val sessions = service.buildMarketSessions(usNow(2025, 4, 14, 6, 0))
        val status = service.buildMarketStatus(sessions)
        assertEquals("PRE_MARKET", status)
    }

    // ── 이스터 / 굿프라이데이 ─────────────────────────────────────────

    @Test
    fun `굿프라이데이 2025년 4월 18일 - 미국 휴장`() {
        val sessions = service.buildMarketSessions(usNow(2025, 4, 18, 12, 0))
        val us = sessions.first { it.market == "US" }
        assertEquals("CLOSED", us.phase)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────

    private fun koreaNow(hour: Int, minute: Int): ZonedDateTime {
        // 평일(월요일 기준)을 KST로 만들어 UTC로 변환
        val kst = ZonedDateTime.of(2025, 4, 14, hour, minute, 0, 0, ZoneId.of("Asia/Seoul"))
        return kst.withZoneSameInstant(ZoneId.of("UTC"))
    }

    private fun usNow(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime {
        val et = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("America/New_York"))
        return et.withZoneSameInstant(ZoneId.of("UTC"))
    }
}
