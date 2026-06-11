package com.giwon.signaldesk.features.backtest.application

import com.giwon.signaldesk.common.KST

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * 섹터 로테이션 — 섹터 ETF 각각에 시즈널리티 엔진을 돌려 "계절별로 어떤 섹터가 강한가"를 매트릭스로.
 * 기존 SeasonalityBacktestService.report() 재사용(ETF별 캐시 + 본 결과도 캐시). AI 미사용.
 *
 * US: SPDR Select Sector 11종. KR: KODEX/TIGER 섹터 ETF(6자리 코드, 야후 .KS).
 */
@Service
class SectorRotationService(
    private val seasonality: SeasonalityBacktestService,
) {
    @Cacheable(cacheNames = ["seasonality"], key = "'sector:' + #market", unless = "#result == null")
    fun report(market: String): SectorRotationReport? {
        val sectors = sectorsFor(market)
        if (sectors.isEmpty()) return null
        val cost = if (market.equals("KR", ignoreCase = true)) 0.25 else 0.10

        val results = sectors.map { s ->
            CompletableFuture.supplyAsync {
                s to runCatching { seasonality.report(market, s.etf, s.name, 15, cost) }.getOrNull()
            }
        }.map { it.join() }

        val seasonalities = results.mapNotNull { (s, rep) ->
            if (rep == null) null
            else SectorSeasonality(
                key = s.key, name = s.name, etf = s.etf, historyYears = rep.history.years,
                monthly = rep.monthly.map { SectorMonth(it.month, it.meanPct, it.winRatePct, it.tier) },
            )
        }
        if (seasonalities.isEmpty()) return null

        return SectorRotationReport(
            market = market.uppercase(),
            asOf = LocalDateTime.now(KST).toString(),
            currentMonth = LocalDate.now(KST).monthValue,
            sectors = seasonalities,
        )
    }

    private fun sectorsFor(market: String): List<SectorDef> = when (market.uppercase()) {
        "US" -> US_SECTORS
        "KR" -> KR_SECTORS
        else -> emptyList()
    }

    companion object {
        private val US_SECTORS = listOf(
            SectorDef("tech", "기술", "XLK"),
            SectorDef("comm", "커뮤니케이션", "XLC"),
            SectorDef("discretionary", "임의소비재", "XLY"),
            SectorDef("staples", "필수소비재", "XLP"),
            SectorDef("energy", "에너지", "XLE"),
            SectorDef("financials", "금융", "XLF"),
            SectorDef("health", "헬스케어", "XLV"),
            SectorDef("industrials", "산업재", "XLI"),
            SectorDef("materials", "소재", "XLB"),
            SectorDef("realestate", "부동산", "XLRE"),
            SectorDef("utilities", "유틸리티", "XLU"),
        )
        private val KR_SECTORS = listOf(
            SectorDef("semi", "반도체", "091160"),
            SectorDef("battery", "2차전지", "266370"),
            SectorDef("it", "IT", "139260"),
            SectorDef("auto", "자동차", "091180"),
            SectorDef("bank", "은행", "091170"),
            SectorDef("bio", "바이오", "244580"),
            SectorDef("construction", "건설", "117700"),
        )
    }
}

private data class SectorDef(val key: String, val name: String, val etf: String)

data class SectorRotationReport(
    val market: String,
    val asOf: String,
    val currentMonth: Int,
    val sectors: List<SectorSeasonality>,
)

data class SectorSeasonality(
    val key: String,
    val name: String,
    val etf: String,
    val historyYears: Double,
    val monthly: List<SectorMonth>,
)

/** tier: STRONG / WEAK / NOISE */
data class SectorMonth(val month: Int, val meanPct: Double, val winRatePct: Double, val tier: String)
