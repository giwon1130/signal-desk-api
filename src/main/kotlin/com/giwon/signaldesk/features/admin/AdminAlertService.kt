package com.giwon.signaldesk.features.admin

import com.giwon.signaldesk.features.auth.application.UserRepository
import com.giwon.signaldesk.features.push.application.ExpoPushClient
import com.giwon.signaldesk.features.push.application.PushRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 운영자 장애 알림 — 스케줄러/Gemini 등 핵심 작업이 실패하면 운영자 기기로 푸시.
 *
 * 스팸 방지: 같은 subject 는 [MIN_INTERVAL] 안에 1회만. (반복 실패가 매 틱 푸시 폭탄이 되지 않게.)
 * admin 이메일 화이트리스트(AdminGuard 와 동일 소스)의 사용자 기기로만 발송.
 */
@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class AdminAlertService(
    private val users: UserRepository,
    private val pushRepository: PushRepository,
    private val expoPush: ExpoPushClient,
    @Value("\${signal-desk.admin.emails:gwim113000@gmail.com}") adminEmailsRaw: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val adminEmails = adminEmailsRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
    private val lastSentBySubject = ConcurrentHashMap<String, Instant>()

    /** 운영자에게 장애 푸시. 동일 subject 는 1시간 내 중복 억제. */
    fun notifyFailure(subject: String, detail: String) {
        runCatching {
            val now = Instant.now()
            val last = lastSentBySubject[subject]
            if (last != null && Duration.between(last, now) < MIN_INTERVAL) return
            lastSentBySubject[subject] = now

            val devices = adminEmails
                .mapNotNull { users.findByEmail(it)?.id }
                .flatMap { pushRepository.listDevices(it) }
            if (devices.isEmpty()) { log.warn("admin alert — 기기 없음 subject={}", subject); return }

            val messages = devices.map { d ->
                ExpoPushClient.Message(
                    to = d.expoToken,
                    title = "🚨 시데 장애: $subject",
                    body = detail.take(160),
                    data = mapOf("type" to "ADMIN_ALERT", "subject" to subject),
                    // userId 미지정 — 방해금지 무시(운영 장애는 시간 무관 전달).
                )
            }
            expoPush.send(messages)
            log.info("admin alert 발송 — subject={} devices={}", subject, devices.size)
        }.onFailure { log.warn("admin alert 발송 실패 subject={}: {}", subject, it.message) }
    }

    companion object {
        private val MIN_INTERVAL: Duration = Duration.ofHours(1)
    }
}
