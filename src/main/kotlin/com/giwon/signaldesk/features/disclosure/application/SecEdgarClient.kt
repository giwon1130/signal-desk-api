package com.giwon.signaldesk.features.disclosure.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SEC EDGAR 의 "getcurrent" Atom 피드에서 최신 8-K(등) 공시를 가져온다.
 *
 *   GET https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=8-K&output=atom&count=N
 *
 * Atom entry 한 건 형식:
 *   <title>8-K - {Company} ({CIK-padded}) (Filer)</title>
 *   <link href="https://www.sec.gov/Archives/edgar/data/{CIK}/{accNoNoDashes}/{accNo}-index.htm"/>
 *   <id>urn:tag:sec.gov,2008:accession-number={accNo}</id>
 *   <updated>{ISO timestamp ET}</updated>
 *
 * SEC fair-access: User-Agent 에 연락처 포함 필요. 실패 시 빈 리스트(fail-soft).
 */
@Component
class SecEdgarClient(
    @Value("\${signal-desk.integrations.sec-edgar.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.sec-edgar.recent-url:https://www.sec.gov/cgi-bin/browse-edgar}") private val recentUrl: String,
    @Value("\${signal-desk.integrations.sec-edgar.user-agent:signal-desk-personal contact@signaldesk.app}") private val userAgent: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun fetchRecent(formType: String = "8-K", count: Int = 40): List<UsDisclosureItem> {
        if (!enabled) return emptyList()
        return runCatching {
            val uri = URI.create("$recentUrl?action=getcurrent&type=$formType&output=atom&count=$count")
            val req = HttpRequest.newBuilder().uri(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", userAgent)
                .header("Accept", "application/atom+xml, application/xml, text/xml")
                .GET().build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() !in 200..299) {
                log.warn("SEC EDGAR non-2xx: status={}", resp.statusCode())
                return@runCatching emptyList()
            }
            val builderFactory = DocumentBuilderFactory.newInstance()
            val builder = builderFactory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(resp.body()))
            val entries = doc.getElementsByTagName("entry")
            (0 until entries.length).mapNotNull { i ->
                val entry = entries.item(i) as? Element ?: return@mapNotNull null
                parseEntry(entry, formType)
            }
        }.onFailure { log.warn("SEC EDGAR fetch failed: {}", it.message) }
            .getOrDefault(emptyList())
    }

    private fun parseEntry(entry: Element, formType: String): UsDisclosureItem? {
        val title = entry.getElementsByTagName("title").item(0)?.textContent?.trim() ?: return null
        // "8-K - {CompanyName} (1234567890) (Filer)" 또는 "{form} - {CompanyName} ({CIK}) ({Role})"
        val titleMatch = Regex("""^\S+ - (.+?) \((\d{1,10})\) \(.+?\)$""").find(title) ?: return null
        val companyName = titleMatch.groupValues[1].trim()
        val cik = titleMatch.groupValues[2].padStart(10, '0')

        val id = entry.getElementsByTagName("id").item(0)?.textContent.orEmpty()
        val accMatch = Regex("""accession-number=([\d-]+)""").find(id) ?: return null
        val accessionNo = accMatch.groupValues[1]

        val link = (entry.getElementsByTagName("link").item(0) as? Element)?.getAttribute("href").orEmpty()
        val updated = entry.getElementsByTagName("updated").item(0)?.textContent?.trim()

        return UsDisclosureItem(
            accessionNo = accessionNo,
            cik = cik,
            companyName = companyName,
            formType = formType,
            filedAt = updated,
            url = link,
        )
    }
}

data class UsDisclosureItem(
    val accessionNo: String,
    val cik: String,
    val companyName: String,
    val formType: String,
    val filedAt: String?,
    val url: String,
)
