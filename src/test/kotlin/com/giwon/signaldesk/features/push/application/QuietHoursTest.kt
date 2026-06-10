package com.giwon.signaldesk.features.push.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 방해금지 시간창 판정 — 자정 넘김(래퍼라운드) 포함. */
class QuietHoursTest {

    private fun within(h: Int, s: Int, e: Int) = AlertPreferenceService.isWithinQuietHours(h, s, e)

    @Test
    fun `자정 넘김 창 22~7`() {
        // 안: 22,23,0,3,6 / 밖: 7,8,12,21
        listOf(22, 23, 0, 3, 6).forEach { assertThat(within(it, 22, 7)).`as`("hour=$it").isTrue() }
        listOf(7, 8, 12, 21).forEach { assertThat(within(it, 22, 7)).`as`("hour=$it").isFalse() }
    }

    @Test
    fun `같은 날 창 9~17`() {
        listOf(9, 12, 16).forEach { assertThat(within(it, 9, 17)).`as`("hour=$it").isTrue() }
        listOf(8, 17, 18, 23, 0).forEach { assertThat(within(it, 9, 17)).`as`("hour=$it").isFalse() }
    }

    @Test
    fun `start == end 면 항상 false`() {
        (0..23).forEach { assertThat(within(it, 9, 9)).isFalse() }
    }

    @Test
    fun `경계값 — start 포함, end 제외`() {
        assertThat(within(22, 22, 7)).isTrue()   // start 포함
        assertThat(within(7, 22, 7)).isFalse()   // end 제외
    }
}
