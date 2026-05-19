package com.giwon.signaldesk.features.disclosure.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OpenDART corpCode.xml zip 다운로드 후 stock_code → corp_code 매핑 캐시.
 * zip 안에 CORPCODE.xml 하나 — 전체 회사 목록 (상장+비상장, 약 100k건).
 * 상장사만(6자리 stock_code) 메모리에 들고 다님.
 *
 * 24시간 TTL 자체 캐시 (Spring Cache 안 쓰는 이유: zip 다운로드 비용이 커서 단순 메모리 ref 가 적합).
 */
@Component
class DartCorpCodeClient(
    @Value("\${signal-desk.integrations.dart.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.dart.base-url:https://opendart.fss.or.kr}") private val baseUrl: String,
    @Value("\${signal-desk.integrations.dart.api-key:}") private val apiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val cache = AtomicReference<CachedCorpCodes?>(null)
    private val ttl = Duration.ofHours(24)

    /** stock_code(6자리) → CorpCodeEntry. 상장사만. */
    fun loadStockCodeMap(): Map<String, CorpCodeEntry> {
        if (!enabled || apiKey.isBlank()) return emptyMap()
        val cached = cache.get()
        if (cached != null && Duration.between(cached.loadedAt, Instant.now()) < ttl) {
            return cached.map
        }
        return runCatching { fetch().also { cache.set(CachedCorpCodes(it, Instant.now())) } }
            .onFailure { log.warn("dart corpCode fetch failed: ${it.message}") }
            .getOrElse { cached?.map ?: emptyMap() }
    }

    private fun fetch(): Map<String, CorpCodeEntry> {
        val url = "$baseUrl/api/corpCode.xml?crtfc_key=$apiKey"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) {
            log.warn("dart corpCode http {}", resp.statusCode())
            return emptyMap()
        }
        val xmlBytes = extractXml(resp.body()) ?: return emptyMap()
        return parseXml(xmlBytes)
    }

    private fun extractXml(zipBytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".xml", ignoreCase = true)) {
                    return zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun parseXml(xml: ByteArray): Map<String, CorpCodeEntry> {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml))
        val nodes = doc.getElementsByTagName("list")
        val result = HashMap<String, CorpCodeEntry>(nodes.length / 4)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val stockCode = el.getElementsByTagName("stock_code").item(0)?.textContent?.trim().orEmpty()
            if (stockCode.length != 6 || !stockCode.all(Char::isDigit)) continue
            val corpCode = el.getElementsByTagName("corp_code").item(0)?.textContent?.trim().orEmpty()
            val corpName = el.getElementsByTagName("corp_name").item(0)?.textContent?.trim().orEmpty()
            if (corpCode.isBlank() || corpName.isBlank()) continue
            result[stockCode] = CorpCodeEntry(corpCode, corpName, stockCode)
        }
        log.info("dart corpCode loaded. listed={}", result.size)
        return result
    }

    private data class CachedCorpCodes(val map: Map<String, CorpCodeEntry>, val loadedAt: Instant)
}
