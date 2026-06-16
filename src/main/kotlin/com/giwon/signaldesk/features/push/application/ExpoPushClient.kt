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
    // jdbc 모드에서만 존재 — DeviceNotRegistered 토큰 정리용(없으면 정리 스킵). 순환참조 없음(저수준 repo).
    @Autowired(required = false) private val pushRepository: PushRepository? = null,
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
        // Expo push API 는 요청당 100건 제한 — 초과 시 배치 전체가 거부되므로 청크로 나눠 보낸다.
        gated.chunked(100).forEach { sendChunk(it) }
    }

    private fun sendChunk(chunk: List<Message>) {
        runCatching {
            val payload = objectMapper.writeValueAsString(chunk)
            val req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("Expo push rejected. status={}, count={}, bodyPrefix={}", resp.statusCode(), chunk.size, resp.body().take(300))
                return
            }
            // 티켓 단위 오류(DeviceNotRegistered 등)는 200 응답 안에 들어온다 — 조용히 삼키지 않게 토큰과 함께 남긴다.
            val tickets = runCatching { objectMapper.readTree(resp.body())["data"] }.getOrNull()
            var errorCount = 0
            tickets?.forEachIndexed { i, t ->
                if (t["status"]?.asText() == "error") {
                    errorCount++
                    val detail = t["details"]?.get("error")?.asText() ?: t["message"]?.asText() ?: "unknown"
                    val token = chunk.getOrNull(i)?.to
                    log.warn("Expo push ticket error. token={}, error={}", token, detail)
                    // 죽은 토큰은 제거 — 앱 삭제/토큰 갱신 후 영구 실패 토큰이 매 발송마다 쌓이지 않게.
                    if (detail == "DeviceNotRegistered" && token != null) {
                        runCatching { pushRepository?.deleteByToken(token) }
                            .onSuccess { if ((it ?: 0) > 0) log.info("dead push token pruned. token={}", token) }
                    }
                }
            }
            log.info("Expo push sent. count={}, ticketErrors={}", chunk.size, errorCount)
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
