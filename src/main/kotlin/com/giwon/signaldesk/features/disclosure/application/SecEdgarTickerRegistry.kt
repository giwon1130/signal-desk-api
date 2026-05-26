package com.giwon.signaldesk.features.disclosure.application

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * SEC EDGAR 의 CIK ↔ ticker 매핑 캐시.
 * https://www.sec.gov/files/company_tickers.json (10K+ 회사) 를 부팅 시 1회 + 매일 1회 갱신.
 *
 * SEC fair access policy: User-Agent 헤더에 연락처 포함 필요.
 */
@Component
class SecEdgarTickerRegistry(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.sec-edgar.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.sec-edgar.tickers-url:https://www.sec.gov/files/company_tickers.json}") private val tickersUrl: String,
    @Value("\${signal-desk.integrations.sec-edgar.user-agent:signal-desk-personal contact@signaldesk.app}") private val userAgent: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Volatile private var cikToTicker: Map<String, String> = emptyMap()
    @Volatile private var loadedAt: Instant = Instant.EPOCH

    @PostConstruct
    fun init() {
        // 부팅 시 1회 비동기 로드 — 실패해도 앱 시작은 막지 않음.
        Thread { runCatching { refresh() }.onFailure { log.warn("SEC ticker registry initial load failed: {}", it.message) } }.start()
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    fun refreshDaily() {
        runCatching { refresh() }.onFailure { log.warn("SEC ticker registry daily refresh failed: {}", it.message) }
    }

    private fun refresh() {
        if (!enabled) return
        val req = HttpRequest.newBuilder()
            .uri(URI.create(tickersUrl))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET().build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            log.warn("SEC ticker registry non-2xx: {}", resp.statusCode())
            return
        }
        val tree = objectMapper.readTree(resp.body())
        val map = mutableMapOf<String, String>()
        tree.fields().forEach { (_, v) ->
            val cik = v["cik_str"]?.asInt()?.toString()?.padStart(10, '0') ?: return@forEach
            val ticker = v["ticker"]?.asText()?.uppercase() ?: return@forEach
            map[cik] = ticker
        }
        cikToTicker = map
        loadedAt = Instant.now()
        log.info("SEC ticker registry loaded: {} entries", map.size)
    }

    /** CIK(0-pad 가능) → 우선주식 ticker. 매칭 없으면 null. */
    fun resolveTicker(cik: String): String? {
        val padded = cik.trim().padStart(10, '0')
        return cikToTicker[padded]
    }
}
