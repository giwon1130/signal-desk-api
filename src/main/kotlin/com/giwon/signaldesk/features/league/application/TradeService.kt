package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.domain.MarketScope
import com.giwon.signaldesk.features.league.domain.Trade
import com.giwon.signaldesk.features.league.domain.TradeSide
import com.giwon.signaldesk.features.league.domain.TradingHours
import com.giwon.signaldesk.features.league.repository.LeagueRepository
import com.giwon.signaldesk.features.league.repository.ParticipantRepository
import com.giwon.signaldesk.features.league.repository.TradeRepository
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.MarketSessionService
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 거래 (Trade) 실행 + 시세 lock + 검증.
 *
 * 흐름:
 *  1. league/participant 검증 (RUNNING 여부, 참가자 여부)
 *  2. 시장 시간 검증 (MARKET_HOURS_ONLY)
 *  3. 시세 fetch (KR=Naver, US=NaverGlobal)
 *  4. 환율 fetch (다른 통화 league 일 때)
 *  5. notional/fee 산출
 *  6. 현금/보유 검증
 *  7. trade INSERT (immutable) + participant.cashBalance 업데이트
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class TradeService(
    private val leagues: LeagueRepository,
    private val participants: ParticipantRepository,
    private val trades: TradeRepository,
    private val positions: PositionService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
    private val fred: FredIndexClient,
    private val marketSession: MarketSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 매수/매도 실행. 모든 검증 통과 후 trade INSERT + cashBalance 업데이트.
     * 검증 실패 시 IllegalStateException.
     */
    fun placeTrade(
        leagueId: UUID,
        userId: UUID,
        market: String,
        ticker: String,
        side: TradeSide,
        quantity: Int,
        name: String = "",
    ): Trade {
        require(quantity > 0) { "quantity must be > 0" }
        val ticker = ticker.trim().uppercase()
        require(market == "KR" || market == "US") { "market must be KR or US" }

        val league = leagues.findById(leagueId) ?: error("league not found")
        require(league.status == LeagueStatus.RUNNING) {
            "league not running (status=${league.status})"
        }
        // marketScope 검증 — BOTH 면 KR/US 다 허용.
        require(when (league.marketScope) {
            MarketScope.KR -> market == "KR"
            MarketScope.US -> market == "US"
            MarketScope.BOTH -> true
        }) { "market $market not allowed in ${league.marketScope} league" }

        val participant = participants.find(leagueId, userId) ?: error("not a participant")

        // 시장 시간 검증 (MARKET_HOURS_ONLY).
        if (league.tradingHours == TradingHours.MARKET_HOURS_ONLY) {
            require(isMarketOpen(market)) {
                "market $market is closed (trading_hours = MARKET_HOURS_ONLY)"
            }
        }

        // 시세 + 종목명 fetch. 종목명은 클라이언트 제공값 우선 (시세 API 엔 종목명이 없어 ticker 로 fallback).
        val (originalPrice, originalCurrency, fetchedName) = fetchLockedPrice(market, ticker)
        val resolvedName = name.trim().ifBlank { fetchedName }

        // 환율 lock — league 통화와 시장 통화 다르면 변환.
        val exchangeRate = resolveExchangeRate(originalCurrency, league.currency)
        val pricePerShare = originalPrice.multiply(exchangeRate).setScale(4, RoundingMode.HALF_UP)
        val notional = pricePerShare.multiply(BigDecimal(quantity)).setScale(0, RoundingMode.HALF_UP).toLong()
        val fee = BigDecimal(notional).multiply(league.fee).setScale(0, RoundingMode.HALF_UP).toLong()

        // 검증
        if (side == TradeSide.BUY) {
            val totalCost = notional + fee
            require(participant.cashBalance >= totalCost) {
                "insufficient cash: need=$totalCost have=${participant.cashBalance}"
            }
        } else {
            // SELL — 보유 수량 검증.
            val pos = positions.positionsForUser(leagueId, userId)
                .firstOrNull { it.market == market && it.ticker == ticker }
                ?: error("no holding to sell")
            require(pos.quantity >= quantity) {
                "insufficient quantity: have=${pos.quantity} sell=$quantity"
            }
        }

        val now = Instant.now()
        val trade = Trade(
            id = UUID.randomUUID(),
            leagueId = leagueId, userId = userId,
            market = market, ticker = ticker, name = resolvedName,
            side = side, quantity = quantity,
            originalPrice = originalPrice, originalCurrency = originalCurrency,
            price = pricePerShare, exchangeRate = exchangeRate,
            priceLockedAt = now,
            feeAmount = fee,
            notionalAmount = notional,
            executedAt = now,
        )
        trades.insert(trade)

        // cashBalance 업데이트.
        val newBalance = when (side) {
            TradeSide.BUY -> participant.cashBalance - notional - fee
            TradeSide.SELL -> participant.cashBalance + notional - fee
        }
        participants.updateCashBalance(leagueId, userId, newBalance)

        log.info("trade — league={} user={} {} {} {}x{}@{} fee={} balance: {}→{}",
            leagueId, userId, side, market, ticker, quantity, pricePerShare, fee,
            participant.cashBalance, newBalance)
        return trade
    }

    /**
     * 시세 fetch — 시장에 맞는 client 사용. 종목명까지 같이 반환.
     * @return Triple(price, currency, name)
     */
    private fun fetchLockedPrice(market: String, ticker: String): Triple<BigDecimal, LeagueCurrency, String> {
        return when (market) {
            "KR" -> {
                val q = krQuotes.fetchKoreanQuotes(listOf(ticker))[ticker]
                    ?: error("price not available for KR:$ticker")
                require(q.currentPrice > 0) { "invalid price for KR:$ticker" }
                Triple(BigDecimal(q.currentPrice), LeagueCurrency.KRW, ticker)
            }
            "US" -> {
                val q = usQuotes.fetchUsQuotes(listOf(ticker))[ticker]
                    ?: error("price not available for US:$ticker")
                require(q.currentPrice > 0) { "invalid price for US:$ticker" }
                Triple(BigDecimal(q.currentPrice), LeagueCurrency.USD, ticker)
            }
            else -> error("unknown market: $market")
        }
    }

    /**
     * 환율 결정 — 시장 통화 → league 통화 변환 계수.
     *  - 같은 통화: 1
     *  - USD → KRW: 1 USD = N KRW (FRED DEXKOUS 의 currentValue)
     *  - KRW → USD: 1 KRW = 1/N USD
     *
     * 시세 stale 시 게임 중단 — 환율 없으면 거래 reject.
     */
    private fun resolveExchangeRate(from: LeagueCurrency, to: LeagueCurrency): BigDecimal {
        if (from == to) return BigDecimal.ONE
        val macro = fred.fetchMacro()
        val rate = macro?.usdKrw?.currentValue
            ?: error("exchange rate USD/KRW not available — try later")
        require(rate > 0) { "invalid exchange rate: $rate" }
        return when {
            from == LeagueCurrency.USD && to == LeagueCurrency.KRW -> BigDecimal(rate).setScale(4, RoundingMode.HALF_UP)
            from == LeagueCurrency.KRW && to == LeagueCurrency.USD ->
                BigDecimal.ONE.divide(BigDecimal(rate), 6, RoundingMode.HALF_UP)
            else -> BigDecimal.ONE
        }
    }

    private fun isMarketOpen(market: String): Boolean {
        return when (market) {
            "KR" -> {
                val now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
                if (!marketSession.isKrTradingDay(now.toLocalDate())) return false
                val t = now.toLocalTime()
                t >= LocalTime.of(9, 0) && t < LocalTime.of(15, 31)
            }
            "US" -> {
                val nowUs = ZonedDateTime.now(ZoneId.of("America/New_York"))
                if (!marketSession.isUsTradingDay(nowUs.toLocalDate())) return false
                val t = nowUs.toLocalTime()
                t >= LocalTime.of(9, 30) && t < LocalTime.of(16, 0)
            }
            else -> false
        }
    }

    fun listLeagueTrades(leagueId: UUID, limit: Int = 100) = trades.findByLeague(leagueId, limit)
    fun listMyTrades(leagueId: UUID, userId: UUID) = trades.findByUserInLeague(leagueId, userId)
}
