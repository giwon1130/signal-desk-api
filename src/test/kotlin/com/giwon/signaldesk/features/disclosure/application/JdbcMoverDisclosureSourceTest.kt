package com.giwon.signaldesk.features.disclosure.application

import com.giwon.signaldesk.features.market.application.MoverReasonTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class JdbcMoverDisclosureSourceTest {
    private val repo = mock(DisclosureSeenRepository::class.java)
    private val source = JdbcMoverDisclosureSource(mock(JdbcTemplate::class.java), repo)
    private val target = MoverReasonTarget("KR", "005930", "삼성전자", 5.0)
    private val now = Instant.parse("2026-10-06T02:00:00Z")
    private val filing = Disclosure("20261006000001", "", "삼성전자", "005930", "단일판매 공급계약", "20261006", "")

    @Test fun `reuse stored matching material filings and retain date only precision`() {
        `when`(repo.findRecentByStockCodes(listOf("005930"), 20)).thenReturn(listOf(filing,
            filing.copy(stockCode = "000660"), filing.copy(rceptNo = "bad/id"), filing.copy(reportNm = "주주총회소집")))
        val result = source.find(target, now)
        assertThat(result.status).isEqualTo("COLLECTED_ONLY")
        assertThat(result.items).hasSize(1)
        assertThat(result.items.single().publishedAt).isNull()
        assertThat(result.items.single().publishedDate).isEqualTo("2026-10-06")
        assertThat(result.items.single().url).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261006000001")
    }

    @Test fun `database failure is not no disclosures`() {
        `when`(repo.findRecentByStockCodes(listOf("005930"), 20)).thenThrow(IllegalStateException("offline"))
        assertThat(source.find(target, now).status).isEqualTo("UNAVAILABLE")
    }
}
