package com.giwon.signaldesk.features.push.application

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Component
class ExpoPushClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.expo.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.expo.endpoint:https://exp.host/--/api/v2/push/send}") private val endpoint: String,
    // jdbc 모드에서만 존재 — 없으면(방해금지 미적용) 전량 발송. 순환참조 없음(AlertPreferenceService 는 ExpoPushClient 미참조).
    @Autowired(required = false) private val alertPreferenceService: AlertPreferenceService? = null,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    data class Message(
        val to: String,
        val title: String,
        val body: String,
        val data: Map<String, Any?> = emptyMap(),
        // 방해금지 판정용 — Expo 페이로드엔 직렬화 안 함. null 이면 게이트 통과(시스템/소셜 푸시).
        @JsonIgnore val userId: UUID? = null,
    )

    fun send(messages: List<Message>) {
        if (!enabled || messages.isEmpty()) return
        val gated = applyQuietHours(messages)
        if (gated.isEmpty()) return
        runCatching {
            val payload = objectMapper.writeValueAsString(gated)
            val req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            log.info("Expo push sent. status={}, count={}, bodyPrefix={}", resp.statusCode(), gated.size, resp.body().take(200))
        }.onFailure { log.warn("Expo push failed: ${it.message}") }
    }

    /** userId 가 달린 메시지 중, 해당 사용자의 방해금지 창 안인 것을 제거. */
    private fun applyQuietHours(messages: List<Message>): List<Message> {
        val prefs = alertPreferenceService ?: return messages
        val userIds = messages.mapNotNull { it.userId }.toSet()
        if (userIds.isEmpty()) return messages
        val windows = runCatching { prefs.loadQuietHoursFor(userIds) }.getOrElse { return messages }
        if (windows.isEmpty()) return messages
        val nowHour = LocalTime.now(clock).hour
        val (kept, suppressed) = messages.partition { m ->
            val w = m.userId?.let { windows[it] } ?: return@partition true
            !AlertPreferenceService.isWithinQuietHours(nowHour, w.first, w.second)
        }
        if (suppressed.isNotEmpty()) {
            log.info("quiet-hours suppressed {} push(es) at hour={}", suppressed.size, nowHour)
        }
        return kept
    }
}
