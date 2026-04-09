package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class CboeVixClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.cboe.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.cboe.vix-url:https://cdn.cboe.com/api/global/delayed_quotes/options/_VIX.json}") private val vixUrl: String,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun fetchVix(): VixSnapshot? {
        if (!enabled) return null

        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(vixUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val data = objectMapper.readTree(response.body())["data"] ?: return null
            VixSnapshot(
                currentPrice = data["current_price"]?.asDouble() ?: return null,
                priceChange = data["price_change"]?.asDouble() ?: 0.0,
                lastTradeTime = data["last_trade_time"]?.asText().orEmpty(),
            )
        }.getOrNull()
    }
}

data class VixSnapshot(
    val currentPrice: Double,
    val priceChange: Double,
    val lastTradeTime: String,
)
