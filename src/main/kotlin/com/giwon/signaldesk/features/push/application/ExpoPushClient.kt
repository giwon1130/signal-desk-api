package com.giwon.signaldesk.features.push.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class ExpoPushClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.expo.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.expo.endpoint:https://exp.host/--/api/v2/push/send}") private val endpoint: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    data class Message(
        val to: String,
        val title: String,
        val body: String,
        val data: Map<String, Any?> = emptyMap(),
    )

    fun send(messages: List<Message>) {
        if (!enabled || messages.isEmpty()) return
        runCatching {
            val payload = objectMapper.writeValueAsString(messages)
            val req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            log.info("Expo push sent. status={}, count={}, bodyPrefix={}", resp.statusCode(), messages.size, resp.body().take(200))
        }.onFailure { log.warn("Expo push failed: ${it.message}") }
    }
}
