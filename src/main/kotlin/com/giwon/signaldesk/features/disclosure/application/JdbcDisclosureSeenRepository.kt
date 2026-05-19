package com.giwon.signaldesk.features.disclosure.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet

@Component
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class JdbcDisclosureSeenRepository(
    private val jdbc: JdbcTemplate,
) : DisclosureSeenRepository {

    override fun filterUnseen(rceptNos: Collection<String>): Set<String> {
        if (rceptNos.isEmpty()) return emptySet()
        val placeholders = rceptNos.joinToString(",") { "?" }
        val seen = jdbc.query(
            "select rcept_no from signal_desk_disclosure_seen where rcept_no in ($placeholders)",
            { rs, _ -> rs.getString("rcept_no") },
            *rceptNos.toTypedArray(),
        ).toSet()
        return rceptNos.toSet() - seen
    }

    override fun markSeen(disclosures: List<Disclosure>) {
        if (disclosures.isEmpty()) return
        jdbc.batchUpdate(
            """
            insert into signal_desk_disclosure_seen (rcept_no, stock_code, corp_name, report_nm, rcept_dt)
            values (?, ?, ?, ?, ?)
            on conflict (rcept_no) do nothing
            """.trimIndent(),
            disclosures.map { arrayOf(it.rceptNo, it.stockCode, it.corpName, it.reportNm, it.rceptDt) },
        )
    }

    override fun findRecentByStockCodes(stockCodes: Collection<String>, limit: Int): List<Disclosure> {
        val codes = stockCodes.filter { it.isNotBlank() }
        if (codes.isEmpty()) return emptyList()
        val placeholders = codes.joinToString(",") { "?" }
        val params: List<Any> = codes + limit
        return jdbc.query(
            """
            select rcept_no, stock_code, corp_name, report_nm, rcept_dt
            from signal_desk_disclosure_seen
            where stock_code in ($placeholders)
            order by rcept_dt desc, rcept_no desc
            limit ?
            """.trimIndent(),
            rowMapper,
            *params.toTypedArray(),
        )
    }
}

private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
    Disclosure(
        rceptNo = rs.getString("rcept_no"),
        corpCode = "",
        corpName = rs.getString("corp_name"),
        stockCode = rs.getString("stock_code") ?: "",
        reportNm = rs.getString("report_nm"),
        rceptDt = rs.getString("rcept_dt"),
        flrNm = "",
    )
}
