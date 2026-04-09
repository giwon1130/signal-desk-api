package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate

@Component
class FredIndexClient(
    @Value("\${signal-desk.integrations.fred.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.fred.base-url:https://fred.stlouisfed.org/graph/fredgraph.csv}") private val baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun fetchUsIndices(): UsIndicesSnapshot? {
        if (!enabled) return null

        val sp500 = fetchSeries("SP500") ?: return null
        val nasdaq = fetchSeries("NASDAQCOM") ?: return null

        return UsIndicesSnapshot(
            sp500 = sp500,
            nasdaq = nasdaq,
        )
    }

    private fun fetchSeries(seriesId: String): FredSeriesSnapshot? {
        return runCatching {
            val endDate = LocalDate.now()
            val startDate = endDate.minusMonths(6)
            val uri = URI.create("$baseUrl?id=$seriesId&cosd=$startDate&coed=$endDate")
            val rows = fetchCsvRows(uri, seriesId) ?: return null

            if (rows.size < 2) return null

            val currentValue = rows.last()
            val previousValue = rows[rows.lastIndex - 1]
            val changeRate = if (previousValue == 0.0) 0.0 else ((currentValue - previousValue) / previousValue) * 100

            FredSeriesSnapshot(
                currentValue = currentValue,
                changeRate = changeRate,
                chart = rows.takeLast(20),
            )
        }.getOrElse {
            logger.warn("FRED series fetch exception. seriesId={}, message={}", seriesId, it.message)
            null
        }
    }

    private fun fetchCsvRows(uri: URI, seriesId: String): List<Double>? {
        return fetchViaWget(uri, seriesId) ?: fetchViaHttpConnection(uri, seriesId)
    }

    private fun fetchViaHttpConnection(uri: URI, seriesId: String): List<Double>? {
        return runCatching {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "text/csv,*/*")

            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                logger.warn("FRED HTTP fetch failed. seriesId={}, status={}, bodyPrefix={}", seriesId, status, errorBody.take(120))
                return null
            }

            connection.inputStream.bufferedReader().use { parseCsvRows(it.readText()) }
        }.getOrElse {
            logger.warn("FRED HTTP fetch exception. seriesId={}, message={}", seriesId, it.message)
            null
        }
    }

    private fun fetchViaWget(uri: URI, seriesId: String): List<Double>? {
        return runCatching {
            val process = ProcessBuilder("wget", "-qO-", uri.toString())
                .redirectErrorStream(true)
                .start()
            val body = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warn("FRED wget fetch failed. seriesId={}, exitCode={}, bodyPrefix={}", seriesId, exitCode, body.take(120))
                return null
            }
            parseCsvRows(body)
        }.getOrElse {
            logger.warn("FRED wget fetch exception. seriesId={}, message={}", seriesId, it.message)
            null
        }
    }

    private fun parseCsvRows(body: String): List<Double> {
        return body.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size < 2) return@mapNotNull null
                parts[1].toDoubleOrNull()
            }
            .toList()
    }
}

data class UsIndicesSnapshot(
    val sp500: FredSeriesSnapshot,
    val nasdaq: FredSeriesSnapshot,
)

data class FredSeriesSnapshot(
    val currentValue: Double,
    val changeRate: Double,
    val chart: List<Double>,
)
