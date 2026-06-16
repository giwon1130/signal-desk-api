package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.Trade
import com.giwon.signaldesk.features.league.domain.TradeSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * PositionService.aggregate — 머니 수학(이동평균 원가·실현손익·매수수수료 비례 반영) 단위 검증.
 * aggregate 는 trade 리스트만으로 계산하는 순수 함수라 외부 의존성은 mock 으로 채운다.
 */
class PositionServiceAggregateTest {

    private val service = PositionService(mock(), mock(), mock(), mock(), mock())

    private var seq = 0L
    private fun trade(side: TradeSide, qty: Int, notional: Long, fee: Long, ticker: String = "005930"): Trade =
        Trade(
            id = UUID.randomUUID(),
            leagueId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            market = "KR", ticker = ticker, name = "삼성전자",
            side = side, quantity = qty,
            originalPrice = BigDecimal.ZERO, originalCurrency = LeagueCurrency.KRW,
            price = BigDecimal.ZERO, exchangeRate = BigDecimal.ONE,
            priceLockedAt = Instant.now(),
            feeAmount = fee, notionalAmount = notional,
            executedAt = Instant.ofEpochSecond(1_700_000_000 + seq++),
        )

    @Test
    fun `단일 매수 — 수량·평단·실현손익 0`() {
        val pos = service.aggregate(listOf(trade(TradeSide.BUY, 10, 1000, 3))).single()
        assertThat(pos.quantity).isEqualTo(10)
        assertThat(pos.averageCost).isEqualByComparingTo(BigDecimal("100.0000"))
        assertThat(pos.realizedPnl).isZero()
        assertThat(pos.totalBuyNotional).isEqualTo(1000)
    }

    @Test
    fun `전량 매도 — 실현손익에 매수·매도 수수료 모두 반영`() {
        // BUY 10 @notional1000 fee3 → 평단100, 주당매수수수료0.3
        // SELL 10 @notional1200 fee4 → costForSell=(100+0.3)*10=1003 → pnl=1200-1003-4=193
        val pos = service.aggregate(
            listOf(trade(TradeSide.BUY, 10, 1000, 3), trade(TradeSide.SELL, 10, 1200, 4)),
        ).single()
        assertThat(pos.quantity).isZero()
        assertThat(pos.realizedPnl).isEqualTo(193)
    }

    @Test
    fun `부분 매도 — 잔여 수량·평단 유지, 비례 매수수수료 반영`() {
        // BUY 10 @1000 fee3 (평단100, 매수수수료주당0.3)
        // SELL 4 @480 fee2 → cost=(100+0.3)*4=401.2→401 → pnl=480-401-2=77
        val pos = service.aggregate(
            listOf(trade(TradeSide.BUY, 10, 1000, 3), trade(TradeSide.SELL, 4, 480, 2)),
        ).single()
        assertThat(pos.quantity).isEqualTo(6)
        assertThat(pos.averageCost).isEqualByComparingTo(BigDecimal("100.0000"))
        assertThat(pos.realizedPnl).isEqualTo(77)
        assertThat(pos.totalSellNotional).isEqualTo(480)
    }

    @Test
    fun `매수 수수료를 무시하지 않는다 — 수수료 반영분만큼 실현손익이 작다`() {
        // 같은 거래에서 매수수수료(주당0.3×10=3)만큼 실현손익이 줄어드는지: fee 없는 가정 대비 정확히 3 작아야 함.
        val withFee = service.aggregate(
            listOf(trade(TradeSide.BUY, 10, 1000, 3), trade(TradeSide.SELL, 10, 1200, 0)),
        ).single().realizedPnl
        // 매수수수료 0 이면 pnl=1200-1000-0=200, 매수수수료3 이면 1200-1003-0=197 → 정확히 3 차이
        assertThat(withFee).isEqualTo(197)
    }
}
