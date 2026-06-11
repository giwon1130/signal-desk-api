package com.giwon.signaldesk.features.league.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Trading League (친구 모의투자) 도메인 모델.
 * spec: signal-desk-app/docs/mock-investment-game-spec.md
 */

enum class LeagueStatus { DRAFT, OPEN, RUNNING, FINISHED }
enum class MarketScope { KR, US, BOTH }
enum class LeagueCurrency { KRW, USD }
enum class TradingHours { MARKET_HOURS_ONLY, ALWAYS }
enum class LeagueVisibility { OPEN, CLOSED }
enum class TradeSide { BUY, SELL }

data class League(
    val id: UUID,
    val name: String,
    val hostUserId: UUID,
    val joinCode: String,
    val marketScope: MarketScope,
    val currency: LeagueCurrency,
    val startingCapital: Long,        // league 통화 정수 (KRW=원, USD=cents)
    val startedAt: Instant,
    val endsAt: Instant,
    val status: LeagueStatus,
    val tradingHours: TradingHours,
    val fee: BigDecimal,              // 0.003 = 0.3%
    val maxPositionPct: BigDecimal,   // 0.30 = 30%
    val visibility: LeagueVisibility,
    val createdAt: Instant,
)

data class Participant(
    val leagueId: UUID,
    val userId: UUID,
    val joinedAt: Instant,
    val nickname: String,
    val avatarEmoji: String,
    val cashBalance: Long,            // league 통화
    val finalReturnRate: BigDecimal?, // 정산 후만
    val finalRank: Int?,
)

data class Trade(
    val id: UUID,
    val leagueId: UUID,
    val userId: UUID,
    val market: String,               // 'KR' | 'US' (Position 합산용)
    val ticker: String,
    val name: String,
    val side: TradeSide,
    val quantity: Int,
    val originalPrice: BigDecimal,    // 시장 원 통화
    val originalCurrency: LeagueCurrency,
    val price: BigDecimal,            // league 통화 환산
    val exchangeRate: BigDecimal,     // 시점 USD/KRW lock (같은 통화면 1.0)
    val priceLockedAt: Instant,
    val feeAmount: Long,
    val notionalAmount: Long,         // league 통화 정수
    val executedAt: Instant,
)

/**
 * Position — DB 저장 X, trade 합산으로 derived.
 * 이동평균법 (FIFO 아님) — 매수 누적 / 매수 누적 수량.
 */
data class Position(
    val market: String,
    val ticker: String,
    val name: String,
    val quantity: Int,                // SUM(BUY.qty) - SUM(SELL.qty)
    val averageCost: BigDecimal,      // SUM(BUY.notional) / SUM(BUY.qty) (이동평균)
    val realizedPnl: Long,            // 실현손익 (league 통화)
    val totalBuyNotional: Long,
    val totalSellNotional: Long,
)
