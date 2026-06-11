package com.giwon.signaldesk.features.league.application

import com.giwon.signaldesk.features.league.domain.League
import com.giwon.signaldesk.features.league.domain.LeagueCurrency
import com.giwon.signaldesk.features.league.domain.LeagueStatus
import com.giwon.signaldesk.features.league.domain.Participant
import com.giwon.signaldesk.features.league.domain.Position
import com.giwon.signaldesk.features.league.repository.LeagueRepository
import com.giwon.signaldesk.features.league.repository.ParticipantRepository
import com.giwon.signaldesk.features.league.repository.TradeRepository
import com.giwon.signaldesk.features.market.application.FredIndexClient
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * League 실시간 리더보드 — 각 participant 의 평가금/수익률 계산.
 *
 * 평가금 = cashBalance + Σ(position.qty × currentPrice [league 통화 환산])
 * 수익률 = (평가금 - startingCapital) / startingCapital
 *
 * 시세 fetch 는 모든 participant 의 보유 종목 union 한 번에 (50회 → 1~2회).
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class LeaderboardService(
    private val leagues: LeagueRepository,
    private val participants: ParticipantRepository,
    private val trades: TradeRepository,
    private val positions: PositionService,
    private val krQuotes: NaverFinanceQuoteClient,
    private val usQuotes: NaverGlobalQuoteClient,
    private val fred: FredIndexClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun entries(leagueId: UUID): List<LeaderboardEntry> {
        val league = leagues.findById(leagueId) ?: return emptyList()
        val parts = participants.findByLeague(leagueId)
        if (parts.isEmpty()) return emptyList()

        // FINISHED 면 final 값 그대로 사용.
        if (league.status == LeagueStatus.FINISHED) {
            return parts
                .sortedBy { it.finalRank ?: Int.MAX_VALUE }
                .map { p ->
                    LeaderboardEntry(
                        userId = p.userId, nickname = p.nickname, avatarEmoji = p.avatarEmoji,
                        cashBalance = p.cashBalance, evaluatedAssets = p.cashBalance,
                        totalAssets = p.cashBalance,
                        returnRate = p.finalReturnRate?.toDouble() ?: 0.0,
                        rank = p.finalRank ?: 0, positionCount = 0,
                    )
                }
        }

        // RUNNING/OPEN — 현재가 fetch 후 평가. 포지션은 거래 1회 조회로 일괄 합산 (참가자별 쿼리 N+1 방지).
        val positionsByUser = positions.positionsByUser(leagueId)
        val allPositions = parts.associateWith { positionsByUser[it.userId].orEmpty() }
        val krTickers = allPositions.values.flatten().filter { it.market == "KR" }.map { it.ticker }.toSet()
        val usTickers = allPositions.values.flatten().filter { it.market == "US" }.map { it.ticker }.toSet()
        val krPrices = if (krTickers.isNotEmpty()) runCatching { krQuotes.fetchKoreanQuotes(krTickers) }.getOrNull().orEmpty() else emptyMap()
        val usPrices = if (usTickers.isNotEmpty()) runCatching { usQuotes.fetchUsQuotes(usTickers) }.getOrNull().orEmpty() else emptyMap()
        val exRate = if (league.marketScope == com.giwon.signaldesk.features.league.domain.MarketScope.BOTH) {
            runCatching { fred.fetchMacro()?.usdKrw?.currentValue ?: 1.0 }.getOrDefault(1.0)
        } else 1.0

        val unsorted = parts.map { p ->
            val pos = allPositions[p] ?: emptyList()
            val evaluated = pos.sumOf { evaluatePosition(it, league, krPrices, usPrices, exRate) }
            val total = p.cashBalance + evaluated
            val ret = if (league.startingCapital > 0)
                (total - league.startingCapital).toDouble() / league.startingCapital else 0.0
            LeaderboardEntry(
                userId = p.userId, nickname = p.nickname, avatarEmoji = p.avatarEmoji,
                cashBalance = p.cashBalance, evaluatedAssets = evaluated,
                totalAssets = total, returnRate = ret,
                rank = 0,  // 정렬 후 부여
                positionCount = pos.size,
            )
        }
        return unsorted.sortedByDescending { it.totalAssets }
            .mapIndexed { i, e -> e.copy(rank = i + 1) }
    }

    /** league 통화 기준 평가금 (position 한 종목). */
    private fun evaluatePosition(
        p: Position, league: League,
        krPrices: Map<String, com.giwon.signaldesk.features.market.application.StockQuote>,
        usPrices: Map<String, com.giwon.signaldesk.features.market.application.StockQuote>,
        usdKrwRate: Double,
    ): Long {
        val priceMap = if (p.market == "KR") krPrices else usPrices
        val q = priceMap[p.ticker] ?: return p.quantity * p.averageCost.toLong()
        // 시세 통화 → league 통화 변환.
        val originalCurrency = if (p.market == "KR") LeagueCurrency.KRW else LeagueCurrency.USD
        val ex = when {
            originalCurrency == league.currency -> 1.0
            originalCurrency == LeagueCurrency.USD && league.currency == LeagueCurrency.KRW -> usdKrwRate
            originalCurrency == LeagueCurrency.KRW && league.currency == LeagueCurrency.USD -> 1.0 / usdKrwRate
            else -> 1.0
        }
        val priceInLeagueCcy = BigDecimal.valueOf(q.exactPrice).multiply(BigDecimal(ex))
            .setScale(4, RoundingMode.HALF_UP)
        return priceInLeagueCcy.multiply(BigDecimal(p.quantity))
            .setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** 정산 — endsAt 도달 시 호출 (LeagueSchedulerService 에서). */
    fun finalize(leagueId: UUID) {
        val league = leagues.findById(leagueId) ?: error("league not found")
        require(league.status == LeagueStatus.RUNNING) { "league not RUNNING" }
        // 현재 시세로 모든 잔여 보유 평가 → final 값 계산
        val entries = entries(leagueId)
        // (모든 잔여 position 가상 청산 — DB 에 SELL trade 까지 INSERT 하지는 않고 evaluation 값만 사용.
        //  trade 는 immutable 보장 위해 추가 INSERT 없이 finalReturnRate 만 저장.)
        entries.forEach { e ->
            val rate = BigDecimal(e.returnRate).setScale(5, RoundingMode.HALF_UP)
            participants.finalizeRanking(leagueId, e.userId, rate, e.rank)
        }
        leagues.updateStatus(leagueId, LeagueStatus.FINISHED)
        log.info("league finalized — id={} winners={}", leagueId,
            entries.take(3).joinToString(", ") { "${it.rank}. ${it.nickname} ${"%+.2f".format(it.returnRate * 100)}%" })
    }
}

data class LeaderboardEntry(
    val userId: UUID,
    val nickname: String,
    val avatarEmoji: String,
    val cashBalance: Long,
    val evaluatedAssets: Long,
    val totalAssets: Long,
    val returnRate: Double,
    val rank: Int,
    val positionCount: Int,
)
