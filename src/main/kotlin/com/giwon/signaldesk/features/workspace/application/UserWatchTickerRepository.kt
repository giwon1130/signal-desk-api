package com.giwon.signaldesk.features.workspace.application

import com.giwon.signaldesk.common.isKrStockCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 사용자 보유(portfolio) + 관심(watchlist) 티커 조회 전용 리포지토리.
 *
 * DART/SEC 공시 매칭·모닝 브리프 개인화·숨은 시그널이 제각각 들고 있던 동일 SQL 을 한곳으로 모음.
 *
 * 시장별 정규화 규칙 (기존 호출부 동작 그대로):
 *  - KR: 6자리 숫자 stock_code 만 유효 (isKrStockCode)
 *  - US: 티커 대문자 정규화
 */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class UserWatchTickerRepository(private val jdbc: JdbcTemplate) {

    /**
     * 모든 사용자의 보유/관심 티커 — userId → ticker Set.
     * user_id 가 null 인 레거시 row 는 제외. market: "KR" | "US".
     */
    fun tickersByUser(market: String): Map<UUID, Set<String>> {
        val watch = jdbc.query(
            "select user_id, ticker from signal_desk_watchlist where market = ? and user_id is not null",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
            market,
        )
        val portfolio = jdbc.query(
            "select user_id, ticker from signal_desk_portfolio_positions where market = ? and user_id is not null",
            { rs, _ -> UUID.fromString(rs.getString("user_id")) to rs.getString("ticker") },
            market,
        )
        return (watch + portfolio)
            .mapNotNull { (userId, ticker) -> normalizeOrNull(market, ticker)?.let { userId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    /** 단일 사용자의 보유/관심 티커 Set — 정규화 없음 (저장된 ticker 그대로). */
    fun tickersForUser(userId: UUID, market: String): Set<String> =
        jdbc.queryForList(
            """
            select ticker from signal_desk_watchlist where user_id = ?::uuid and market = ?
            union
            select ticker from signal_desk_portfolio_positions where user_id = ?::uuid and market = ?
            """.trimIndent(),
            String::class.java,
            userId.toString(), market, userId.toString(), market,
        ).toSet()

    /** 단일 사용자의 보유/관심 ticker → 종목명. KR=6자리 검증, US=공백 제외 + 대문자 키. */
    fun namedTickersForUser(userId: UUID, market: String): Map<String, String> {
        val rows = jdbc.query(
            """
            select ticker, name from signal_desk_watchlist where user_id = ?::uuid and market = ?
            union
            select ticker, name from signal_desk_portfolio_positions where user_id = ?::uuid and market = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("ticker") to rs.getString("name") },
            userId.toString(), market, userId.toString(), market,
        )
        return when (market) {
            "KR" -> rows.filter { it.first.isKrStockCode() }.associate { it.first to it.second }
            else -> rows.filter { it.first.isNotBlank() }.associate { it.first.uppercase() to it.second }
        }
    }

    /** 전체 조회용 정규화 — KR 은 6자리 숫자만 통과, US 는 대문자. */
    private fun normalizeOrNull(market: String, ticker: String): String? = when (market) {
        "KR" -> ticker.takeIf { it.isKrStockCode() }
        else -> ticker.uppercase()
    }
}
