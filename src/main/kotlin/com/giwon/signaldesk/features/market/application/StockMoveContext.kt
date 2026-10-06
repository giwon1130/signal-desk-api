package com.giwon.signaldesk.features.market.application

import java.net.URI
import java.time.*
import java.util.Locale

/** Evidence of an event is not proof that the event caused a price move. */
data class MoveEvidence(
    val kind: String, val title: String, val source: String, val url: String,
    val publishedAt: String? = null, val publishedDate: String? = null,
    val timing: String = "RELATED_PERIOD",
)

data class MoveSourceCheck(val source: String, val status: String, val checkedAt: String? = null)
data class StockMoveContext(
    val asOf: String,
    val status: String,
    val summary: String,
    val evidence: List<MoveEvidence> = emptyList(),
    val sourceChecks: List<MoveSourceCheck> = emptyList(),
    val notes: List<String> = emptyList(),
    val rulesVersion: String = "stock-move-evidence-v1",
)

data class NewsSearchResult(val status: String, val items: List<MarketNews> = emptyList())
data class MoveDisclosureResult(val status: String, val items: List<MoveEvidence> = emptyList(), val checkedAt: String? = null)
fun interface MoverDisclosureSource { fun find(target: MoverReasonTarget, now: Instant): MoveDisclosureResult }

internal object StockMoveContextBuilder {
    const val UNAVAILABLE = "관련 자료를 불러오지 못했습니다. 가격 변동부터 확인해 주세요."
    const val NO_CATALYST = "확인한 최근 자료에서는 눈에 띄는 개별 소식을 찾지 못했습니다."

    fun build(target: MoverReasonTarget, now: Instant, disclosures: MoveDisclosureResult,
              news: List<MarketNews>, checks: List<MoveSourceCheck>, report: MarketEvidenceReport?): StockMoveContext {
        val zone = ZoneId.of(if (target.market == "US") "America/New_York" else "Asia/Seoul")
        val date = now.atZone(zone).toLocalDate()
        val filings = disclosures.items.filter { e ->
            safeUrl(e.url) && e.source.isNotBlank() && e.title.length in 3..240 &&
                runCatching { LocalDate.parse(e.publishedDate ?: Instant.parse(e.publishedAt).atZone(zone).toLocalDate().toString()) }
                    .getOrNull()?.let { it in date.minusDays(4)..date } == true &&
                (e.publishedAt == null || runCatching { Instant.parse(e.publishedAt) <= now }.getOrDefault(false))
        }.distinctBy { it.url }.sortedByDescending { it.publishedAt ?: it.publishedDate }.take(3)
        val headlines = MoverNewsEvidence.relevant(target, news, now).take(3).map {
            MoveEvidence("NEWS", MoverNewsEvidence.headline(it), it.source, it.url, publishedAt = it.publishedAt)
        }
        val market = marketEvidence(target, report, now)
        val sources = listOf(MoveSourceCheck(if (target.market == "KR") "DART" else "SEC", disclosures.status, disclosures.checkedAt)) + checks
        val direct = filings + headlines
        val searched = checks.any { it.status == "SUCCESS" }
        val summary = when {
            filings.isNotEmpty() -> filingSummary(filings.first())
            headlines.isNotEmpty() -> "관련 보도(${headlines.first().source}): 「${headlines.first().title}」"
            market != null -> market.title + if (searched) " 개별 소식은 확인한 자료에서 찾지 못했습니다." else " 개별 소식은 자료 조회가 원활하지 않아 확인하지 못했습니다."
            searched -> NO_CATALYST
            else -> UNAVAILABLE
        }
        val notes = buildList {
            add("최근 4일의 공시·보도를 확인합니다. 관련 자료가 있다는 것만으로 주가 변동의 원인이 확정되지는 않습니다.")
            if (disclosures.status == "COLLECTED_ONLY") add("공시는 기존 수집분을 조회했습니다. 누락 가능성이 있어 전체 공시를 확인했다는 의미는 아닙니다.")
            if (filings.any { it.publishedAt == null }) add("일부 공시는 접수 날짜만 확인되어 장중 변동보다 먼저 발표됐는지 판단하지 않았습니다.")
            if (sources.any { it.status == "UNAVAILABLE" }) add("일부 자료 조회에 실패했습니다. 조회 실패를 소식이 없다는 뜻으로 해석하지 않았습니다.")
            if (sources.any { it.status == "DISABLED" }) add("연결되지 않은 자료원이 있어 확인 범위가 제한됩니다.")
            if (market != null) add("시장 동반 흐름은 관측 비교이며 개별 종목의 상승·하락 원인으로 확정하지 않습니다.")
        }
        return StockMoveContext(now.toString(), when {
            direct.isNotEmpty() -> "EVIDENCE_FOUND"
            market != null -> "MARKET_CONTEXT"
            searched -> "NO_RECENT_CATALYST"
            else -> "UNAVAILABLE"
        }, summary, direct + listOfNotNull(market), sources, notes)
    }

    private fun filingSummary(e: MoveEvidence): String {
        val title = e.title.replace(Regex("[\\s()\\[\\]·ㆍ]"), "")
        return when {
            title.contains("정정") -> "기존 공시 내용이 정정됐습니다. 달라진 내용을 먼저 확인해 주세요."
            title.contains("철회") || title.contains("취소") -> "기존 발표의 취소·철회와 관련된 공시가 나왔습니다. 변경 내용을 확인해 주세요."
            title.contains("해지") -> "계약 해지 관련 공시가 나왔습니다. 해지 사유와 영향을 확인해 주세요."
            e.source == "SEC EDGAR" -> "최근 기업 공시가 접수됐습니다. 구체적인 내용은 원문에서 확인해 주세요."
            title.contains("자기주식취득") -> "자사주 매입 관련 공시가 나왔습니다. 매입 규모와 기간을 확인해 주세요."
            title.contains("유상증자") -> "유상증자 관련 공시가 나왔습니다. 발행 규모와 조건을 확인해 주세요."
            title.contains("공급계약") || title.contains("단일판매") -> "판매·공급계약 관련 공시가 나왔습니다. 계약 규모와 기간을 확인해 주세요."
            title.contains("실적") || title.contains("손익구조") -> "실적 관련 공시가 나왔습니다. 매출과 이익 변화를 확인해 주세요."
            else -> "최근 공시: ${e.title}. 공시 내용과 현재 흐름을 함께 확인해 주세요."
        }
    }

    private fun marketEvidence(target: MoverReasonTarget, report: MarketEvidenceReport?, now: Instant): MoveEvidence? {
        if (report == null || !target.changeRate.isFinite() || target.changeRate == 0.0) return null
        val asOf = runCatching { Instant.parse(report.asOf) }.getOrNull() ?: return null
        if (Duration.between(asOf, now).seconds !in 0..120) return null
        val zone = ZoneId.of(if (target.market == "US") "America/New_York" else "Asia/Seoul")
        val today = now.atZone(zone).toLocalDate().toString()
        val ids = if (target.market == "KR") setOf("^KS11", "^KQ11") else setOf("^GSPC", "^IXIC")
        val indices = report.evidence.filter { e ->
            e.id in ids && e.status == "OBSERVED" && e.observationDate == today &&
                e.change?.isFinite() == true && !e.source.isNullOrBlank() && safeUrl(e.sourceUrl.orEmpty()) &&
                runCatching { Duration.between(Instant.parse(e.observedAt), now).seconds in 0..5400 }.getOrDefault(false)
        }
        // Broad-market statement requires both indices; don't select only the agreeing index.
        if (indices.size != 2) return null
        val direction = if (target.changeRate > 0) 1 else -1
        if (indices.any { (it.change!! * direction) < 0.3 }) return null
        val title = indices.joinToString(" · ") { "${it.label} ${String.format(Locale.ROOT, "%+.2f", it.change)}%" } +
            "로, 주요 지수도 같은 방향으로 움직이고 있습니다."
        return MoveEvidence("MARKET", title, indices.mapNotNull { it.source }.distinct().joinToString(" / "),
            indices.first().sourceUrl!!, publishedAt = indices.minOf { it.observedAt!! }, timing = "CO_MOVEMENT_NOT_CAUSE")
    }

    fun safeUrl(value: String): Boolean = runCatching {
        URI(value).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }
    }.getOrDefault(false)
}
