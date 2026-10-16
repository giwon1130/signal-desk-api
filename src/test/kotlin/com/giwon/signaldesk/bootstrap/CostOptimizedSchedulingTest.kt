package com.giwon.signaldesk.bootstrap

import com.giwon.signaldesk.features.backtest.application.*
import com.giwon.signaldesk.features.market.application.*
import com.giwon.signaldesk.features.media.application.*
import com.giwon.signaldesk.features.push.application.WatchlistAlertScheduler
import com.giwon.signaldesk.features.push.application.WatchlistAlertService
import com.giwon.signaldesk.features.reading.application.ReadingCallAlertScheduler
import com.giwon.signaldesk.features.reading.application.ReadingCallAlertService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CostOptimizedSchedulingTest {
    private val runner = ApplicationContextRunner()
        .withPropertyValues("signal-desk.store.mode=jdbc")
        .withUserConfiguration(
            SectorRotationWarmupScheduler::class.java, MoverReasonWarmupScheduler::class.java,
            MorningBriefScheduler::class.java, EveningBriefScheduler::class.java, IntradayBriefScheduler::class.java,
            WatchlistAlertScheduler::class.java, ReadingCallAlertScheduler::class.java,
        )
        .withBean(SectorRotationService::class.java, { mock(SectorRotationService::class.java) })
        .withBean(MoverReasonService::class.java, { mock(MoverReasonService::class.java) })
        .withBean(MarketSessionService::class.java, {
            mock(MarketSessionService::class.java, Answer { call ->
                if (call.method.name in setOf("isKrTradingDay", "isUsTradingDay", "isRegularSession")) true
                else RETURNS_DEFAULTS.answer(call)
            })
        })
        .withBean(MorningBriefService::class.java, { mock(MorningBriefService::class.java) })
        .withBean(EveningBriefService::class.java, { mock(EveningBriefService::class.java) })
        .withBean(IntradayBriefService::class.java, { mock(IntradayBriefService::class.java) })
        .withBean(WatchlistAlertService::class.java, { mock(WatchlistAlertService::class.java) })
        .withBean(ReadingCallAlertService::class.java, { mock(ReadingCallAlertService::class.java) })

    @Test fun `default cost mode skips optional work but all brief and price alert dispatch still works`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(SectorRotationWarmupScheduler::class.java)
                .doesNotHaveBean(MoverReasonWarmupScheduler::class.java)
            context.getBean(MorningBriefScheduler::class.java).runDaily()
            context.getBean(EveningBriefScheduler::class.java).runDaily()
            context.getBean(IntradayBriefScheduler::class.java).runClose()
            context.getBean(WatchlistAlertScheduler::class.java).apply { runKr(); runUs() }
            context.getBean(ReadingCallAlertScheduler::class.java).apply { runKr(); runUs() }

            verify(context.getBean(MorningBriefService::class.java)).runBrief(false)
            verify(context.getBean(EveningBriefService::class.java)).runBrief()
            verify(context.getBean(IntradayBriefService::class.java)).runBrief(IntradayBriefService.Slot.CLOSE, false)
            verify(context.getBean(WatchlistAlertService::class.java)).scanAndNotify("KR")
            verify(context.getBean(WatchlistAlertService::class.java)).scanAndNotify("US")
            verify(context.getBean(ReadingCallAlertService::class.java)).scanAndNotify("KR")
            verify(context.getBean(ReadingCallAlertService::class.java)).scanAndNotify("US")
            verifyNoInteractions(context.getBean(SectorRotationService::class.java), context.getBean(MoverReasonService::class.java))
        }
    }

    @Test fun `optional precomputation requires explicit enable and supports turning it off again`() {
        runner.withPropertyValues(
            "signal-desk.scheduling.sector-rotation-warm.enabled=true",
            "signal-desk.scheduling.mover-reason-warm.enabled=true",
        ).run { context ->
            assertThat(context).hasSingleBean(SectorRotationWarmupScheduler::class.java)
                .hasSingleBean(MoverReasonWarmupScheduler::class.java)
        }
        runner.withPropertyValues(
            "signal-desk.scheduling.sector-rotation-warm.enabled=false",
            "signal-desk.scheduling.mover-reason-warm.enabled=false",
        ).run { context ->
            assertThat(context).doesNotHaveBean(SectorRotationWarmupScheduler::class.java)
                .doesNotHaveBean(MoverReasonWarmupScheduler::class.java)
                .hasSingleBean(MorningBriefScheduler::class.java)
                .hasSingleBean(WatchlistAlertScheduler::class.java)
        }
    }
}
