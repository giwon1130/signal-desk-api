package com.giwon.signaldesk.features.disclosure.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SecSubmissionsClientTest {
    private val mapper=ObjectMapper()
    private val client=SecSubmissionsClient(mapper,SecRequestBudget(),false,"Test contact@example.com","http://127.0.0.1:9")
    private val now=Instant.parse("2026-10-06T15:00:00Z")
    @Test fun `retain exact filing forms including amendments and exclude future old malformed entries`() {
        val forms=listOf("8-K","6-K","10-Q","10-K","20-F","40-F","6-K/A","S-8","8-K","8-K")
        val root=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("cik" to 1234,"name" to "Issuer",
            "filings" to mapOf("recent" to mapOf("form" to forms,
                "accessionNumber" to forms.indices.map { "0000001234-26-${it.toString().padStart(6,'0')}" },
                "acceptanceDateTime" to forms.indices.map { if(it==8) "2026-09-01T10:00:00Z" else if(it==9) "2026-10-07T10:00:00Z" else "2026-10-06T10:00:00Z" }))))
        val result=client.parse(root,"0000001234",now)
        assertThat(result.status).isEqualTo("AVAILABLE")
        assertThat(result.filings).hasSize(7)
        assertThat(result.filings.map{it.formType}).contains("6-K/A","20-F")
        assertThat(result.filings).allMatch{it.url.startsWith("https://www.sec.gov/Archives/edgar/data/1234/")}
        assertThat(client.parse(root,"1235",now).status).isEqualTo("INVALID")
    }
    @Test fun `missing response is not no filings and disabled client makes no request`() {
        assertThat(client.parse(mapper.readTree("{}"),"1234",now).status).isEqualTo("INVALID")
        assertThat(client.fetch("1234").status).isEqualTo("DISABLED")
    }
}
