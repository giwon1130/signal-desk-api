package com.giwon.signaldesk.features.market.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class PizzIntClient(
    @Value("\${signal-desk.integrations.pizzint.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.pizzint.base-url:https://www.pizzint.watch/}") private val baseUrl: String,
) {
    private val doughconRegex = Regex("""DOUGHCON\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val monitoredLocationRegex = Regex("""(\d+)\s+LOCATIONS\s+MONITORED""", RegexOption.IGNORE_CASE)
    private val reportRegex = Regex("""(\d+)\s+REPORTS\s+•\s+(\d+)\s+ALERTS""", RegexOption.IGNORE_CASE)
    private val locationCardRegex = Regex(
        """<h3[^>]*>([^<]+)</h3>.*?<span class="[^"]*font-bold[^"]*">([^<]+)</span>.*?<div class="text-xs text-gray-400 font-mono">([^<]+)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchSignals(): PizzIntSnapshot? {
        if (!enabled) return null

        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val body = normalizeHtml(response.body())

            val doughconLevel = doughconRegex.find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val monitoredLocationCount = monitoredLocationRegex.find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val reportMatch = reportRegex.find(body)

            val locationSignals = locationCardRegex
                .findAll(body)
                .mapNotNull { match ->
                    val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    val status = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    val distance = match.groupValues.getOrNull(3)?.trim().orEmpty()
                    if (!isOperationalLocation(name, status, distance)) {
                        null
                    } else {
                        PizzIntLocationSignal(name = name, status = status, distance = distance)
                    }
                }
                .distinctBy { it.name }
                .toList()

            if (doughconLevel == null && reportMatch == null && locationSignals.isEmpty()) {
                return@runCatching null
            }

            PizzIntSnapshot(
                doughconLevel = doughconLevel,
                monitoredLocationCount = monitoredLocationCount,
                reportCount = reportMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
                alertCount = reportMatch?.groupValues?.getOrNull(2)?.toIntOrNull(),
                locationSignals = locationSignals,
                sourceUrl = baseUrl,
            )
        }.getOrNull()
    }

    private fun normalizeHtml(body: String): String {
        return body
            .replace("<!-- -->", "")
            .replace("&#x27;", "'")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""\s+"""), " ")
    }

    private fun isOperationalLocation(name: String, status: String, distance: String): Boolean {
        if (name.isBlank() || status.isBlank() || distance.isBlank()) return false
        if (!distance.contains("mi", ignoreCase = true)) return false
        if (name.length > 64) return false

        val normalizedStatus = status.uppercase()
        val validStatus = normalizedStatus == "QUIET" ||
            normalizedStatus == "NOMINAL" ||
            normalizedStatus == "NO DATA" ||
            normalizedStatus == "CLOSED" ||
            normalizedStatus.contains("% SPIKE")

        return validStatus
    }
}

data class PizzIntSnapshot(
    val doughconLevel: Int?,
    val monitoredLocationCount: Int?,
    val reportCount: Int?,
    val alertCount: Int?,
    val locationSignals: List<PizzIntLocationSignal>,
    val sourceUrl: String,
)

data class PizzIntLocationSignal(
    val name: String,
    val status: String,
    val distance: String,
)
