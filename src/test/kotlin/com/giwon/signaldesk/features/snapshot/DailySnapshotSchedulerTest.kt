package com.giwon.signaldesk.features.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.scheduling.annotation.Scheduled

class DailySnapshotSchedulerTest {
    @Test fun `US schedule includes Saturday at exchange local date rollover and KR stays isolated`() {
        val service = mock(DailySnapshotService::class.java)
        val scheduler = DailySnapshotScheduler(service)
        scheduler.run()
        scheduler.runUs()
        verify(service).runDailySnapshot("KR")
        verify(service).runDailySnapshot("US")
        val us = DailySnapshotScheduler::class.java.getMethod("runUs").getAnnotation(Scheduled::class.java)
        assertThat(us.zone).isEqualTo("America/New_York")
        assertThat(us.cron).isEqualTo("0 40 0,1 * * *")
        val kr = DailySnapshotScheduler::class.java.getMethod("run").getAnnotation(Scheduled::class.java)
        assertThat(kr.zone).isEqualTo("Asia/Seoul")
        assertThat(kr.cron).isEqualTo("0 40 16,17 * * MON-FRI")
    }
}
