package com.giwon.signaldesk.features.disclosure.application

import com.giwon.signaldesk.features.market.application.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reuse collected filings, without claiming the scan was exhaustive or fetching per user. */
@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcMoverDisclosureSource(private val jdbc: JdbcTemplate, private val dart: DisclosureSeenRepository,
    private val registry: SecEdgarTickerRegistry) : MoverDisclosureSource {
    override fun find(target: MoverReasonTarget, now: Instant): MoveDisclosureResult = runCatching {
        val items = when (target.market) {
            "KR" -> dart.findRecentByStockCodes(listOf(target.ticker), 20)
                .filter { it.stockCode == target.ticker && it.importance != DisclosureImportance.LOW && it.rceptNo.matches(Regex("\\d{14}")) }
                .mapNotNull { d ->
                    val date = runCatching { LocalDate.parse(d.rceptDt, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return@mapNotNull null
                    MoveEvidence("DISCLOSURE", d.reportNm, "DART", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${d.rceptNo}",
                        publishedDate = date.toString(), timing = "DATE_ONLY")
                }
            "US" -> jdbc.query(
                """select accession_no, cik, form_type, filed_at from signal_desk_us_disclosure_seen
                   where (ticker = ? or cik = ?) and filed_at is not null order by filed_at desc limit 20""",
                { rs, _ ->
                    val accession = rs.getString("accession_no")
                    val cik = rs.getString("cik")
                    val time = runCatching { Instant.parse(rs.getString("filed_at")) }.getOrNull()
                    if (!accession.matches(Regex("\\d{10}-\\d{2}-\\d{6}")) || !cik.matches(Regex("\\d{1,10}")) || time == null) null
                    else MoveEvidence("DISCLOSURE", "${rs.getString("form_type")} 기업 공시 접수", "SEC EDGAR",
                        "https://www.sec.gov/Archives/edgar/data/${cik.toLong()}/${accession.replace("-", "")}/$accession-index.htm",
                        publishedAt = time.toString(), publishedDate = time.atZone(ZoneId.of("America/New_York")).toLocalDate().toString())
                }, target.ticker, registry.resolveCik(target.ticker),
            ).filterNotNull()
            else -> return MoveDisclosureResult("DISABLED")
        }
        MoveDisclosureResult("COLLECTED_ONLY", items)
    }.getOrElse { MoveDisclosureResult("UNAVAILABLE") }
}
