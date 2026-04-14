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
            val body = response.body()

            val doughconLevel = Regex("""DOUGHCON\s+(\d)""", RegexOption.IGNORE_CASE)
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val monitoredLocationCount = Regex("""(\d+)\s+LOCATIONS\s+MONITORED""", RegexOption.IGNORE_CASE)
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val reportMatch = Regex("""(\d+)\s+REPORTS\s+•\s+(\d+)\s+ALERTS""", RegexOption.IGNORE_CASE)
                .find(body)

            val locationSignals = Regex(
                """###\s+([A-Z0-9,'&.\-\s]+?)\s+(QUIET|NOMINAL|NO DATA|CLOSED|(\d+)%\s+SPIKE)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            )
                .findAll(body)
                .mapNotNull { match ->
                    val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    val status = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (name.isBlank() || status.isBlank()) null else PizzIntLocationSignal(name, status)
                }
                .take(8)
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
)
