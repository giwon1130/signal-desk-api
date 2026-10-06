package com.giwon.signaldesk.features.disclosure.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.*

data class SecSubmissionResult(val status: String, val filings: List<UsDisclosureItem> = emptyList())

/** Shared in-process budget, below SEC's 10 requests/s ceiling. No retry after blocking responses. */
@Component
class SecRequestBudget {
    private var nextRequest = Instant.EPOCH
    private var blockedUntil = Instant.EPOCH
    @Synchronized fun acquire(): Boolean {
        val now = Instant.now()
        if (now < blockedUntil) return false
        val wait = Duration.between(now, nextRequest).toMillis()
        if (wait > 0) Thread.sleep(wait.coerceAtMost(500))
        nextRequest = Instant.now().plusMillis(500)
        return true
    }
    @Synchronized fun block() { blockedUntil = Instant.now().plusSeconds(300) }
}

@Component
class SecSubmissionsClient(
    private val mapper: ObjectMapper,
    private val budget: SecRequestBudget,
    @Value("\${signal-desk.integrations.sec-edgar.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.sec-edgar.user-agent:signal-desk-personal contact@signaldesk.app}") private val userAgent: String,
    @Value("\${signal-desk.integrations.sec-edgar.submissions-url:https://data.sec.gov/submissions}") private val baseUrl: String,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val cache = Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(10)).build<String, SecSubmissionResult>()

    fun fetch(cik: String): SecSubmissionResult {
        if (!enabled) return SecSubmissionResult("DISABLED")
        if (!cik.matches(Regex("\\d{1,10}"))) return SecSubmissionResult("INVALID")
        return cache.get(cik) {
            runCatching {
                if (!budget.acquire()) return@runCatching SecSubmissionResult("RATE_LIMITED")
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/CIK${cik.padStart(10, '0')}.json"))
                    .header("User-Agent", userAgent).header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6)).GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in setOf(403, 429)) budget.block()
                if (response.statusCode() !in 200..299) return@runCatching SecSubmissionResult("UNAVAILABLE")
                parse(mapper.readTree(response.body()), cik)
            }.getOrDefault(SecSubmissionResult("UNAVAILABLE"))
        }
    }

    internal fun parse(root: JsonNode, cik: String, now: Instant = Instant.now()): SecSubmissionResult {
        if (root["cik"]?.asText()?.trimStart('0') != cik.trimStart('0')) return SecSubmissionResult("INVALID")
        val recent = root["filings"]?.get("recent") ?: return SecSubmissionResult("INVALID")
        val accessionNumbers = recent["accessionNumber"]?.takeIf { it.isArray } ?: return SecSubmissionResult("INVALID")
        val company = root["name"]?.asText()?.takeIf { it.isNotBlank() } ?: return SecSubmissionResult("INVALID")
        val oldest = now.minus(Duration.ofDays(14))
        val filings = accessionNumbers.mapIndexedNotNull { i, value ->
            val accession = value.asText()
            val form = recent["form"]?.get(i)?.asText() ?: return@mapIndexedNotNull null
            if (form.removeSuffix("/A") !in setOf("8-K", "6-K", "10-Q", "10-K", "20-F", "40-F")) return@mapIndexedNotNull null
            if (!accession.matches(Regex("\\d{10}-\\d{2}-\\d{6}"))) return@mapIndexedNotNull null
            val accepted = runCatching { Instant.parse(recent["acceptanceDateTime"]?.get(i)?.asText()) }.getOrNull() ?: return@mapIndexedNotNull null
            if (accepted < oldest || accepted > now.plusSeconds(60)) return@mapIndexedNotNull null
            UsDisclosureItem(accession, cik.padStart(10, '0'), company, form, accepted.toString(),
                "https://www.sec.gov/Archives/edgar/data/${cik.toLong()}/${accession.replace("-", "")}/$accession-index.htm")
        }.distinctBy { it.accessionNo }
        return SecSubmissionResult("AVAILABLE", filings)
    }
}
