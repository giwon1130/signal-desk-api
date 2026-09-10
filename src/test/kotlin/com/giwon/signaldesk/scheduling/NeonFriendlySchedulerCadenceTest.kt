package com.giwon.signaldesk.scheduling

import com.giwon.signaldesk.features.disclosure.application.DartDisclosureScheduler
import com.giwon.signaldesk.features.disclosure.application.SecEdgarDisclosureScheduler
import com.giwon.signaldesk.features.league.application.LeagueSchedulerService
import com.giwon.signaldesk.features.market.application.MarketCacheWarmer
import com.giwon.signaldesk.features.push.application.WatchlistAlertScheduler
import com.giwon.signaldesk.features.reading.application.ReadingCallAlertScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled

class NeonFriendlySchedulerCadenceTest {

    @Test
    fun `database polling jobs are aligned and no more frequent than every fifteen minutes`() {
        assertSchedule(LeagueSchedulerService::class.java, "tick", "0 0,30 * * * *", "UTC")
        assertSchedule(DartDisclosureScheduler::class.java, "runScan", "0 */15 7-19 * * MON-FRI", "Asia/Seoul")
        assertSchedule(SecEdgarDisclosureScheduler::class.java, "runScan", "0 */15 6-20 * * MON-FRI", "America/New_York")
        assertSchedule(WatchlistAlertScheduler::class.java, "runKr", "0 */15 9-15 * * MON-FRI", "Asia/Seoul")
        assertSchedule(WatchlistAlertScheduler::class.java, "runUs", "0 */15 9-15 * * MON-FRI", "America/New_York")
        assertSchedule(ReadingCallAlertScheduler::class.java, "runKr", "0 */15 9-15 * * MON-FRI", "Asia/Seoul")
        assertSchedule(ReadingCallAlertScheduler::class.java, "runUs", "0 */15 9-15 * * MON-FRI", "America/New_York")
    }

    @Test
    fun `market cache warmer requires explicit opt in`() {
        val condition = MarketCacheWarmer::class.java.getAnnotation(ConditionalOnProperty::class.java)

        assertEquals("signal-desk.scheduling.market-cache-warm", condition.prefix)
        assertEquals(listOf("enabled"), condition.name.toList())
        assertEquals("true", condition.havingValue)
        assertFalse(condition.matchIfMissing)
    }

    private fun assertSchedule(type: Class<*>, methodName: String, cron: String, zone: String) {
        val schedule = type.getDeclaredMethod(methodName).getAnnotation(Scheduled::class.java)
        assertEquals(cron, schedule.cron)
        assertEquals(zone, schedule.zone)
    }
}
