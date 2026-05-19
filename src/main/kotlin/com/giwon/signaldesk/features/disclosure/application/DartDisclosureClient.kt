package com.giwon.signaldesk.features.disclosure.application

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenDART list.json — 일자 범위 기준 최신 공시 페이지네이션.
 *
 *   GET /api/list.json?crtfc_key=&bgn_de=YYYYMMDD&end_de=YYYYMMDD
 *                     &page_no=1&page_count=100&sort=date&sort_mth=desc
 *
 * 응답: { status, message, page_no, page_count, total_count, total_page, list: [...] }
 *   - status="000": 정상, "013": 조회된 데이터 없음, 그 외: 오류
 *   - list 항목 stock_code 가 비어있으면 비상장사 → 푸시 대상 제외
 */
@Component
class DartDisclosureClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.dart.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.dart.base-url:https://opendart.fss.or.kr}") private val baseUrl: String,
    @Value("\${signal-desk.integrations.dart.api-key:}") private val apiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun fetchRecent(yyyymmdd: String, pageNo: Int = 1, pageCount: Int = 100): DartListResponse? {
        if (!enabled || apiKey.isBlank()) return null
        val url = "$baseUrl/api/list.json?crtfc_key=$apiKey" +
            "&bgn_de=$yyyymmdd&end_de=$yyyymmdd" +
            "&page_no=$pageNo&page_count=$pageCount" +
            "&sort=date&sort_mth=desc"
        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                log.warn("dart list http {}", resp.statusCode())
                return null
            }
            objectMapper.readValue(resp.body(), DartListResponse::class.java)
        }.onFailure { log.warn("dart list fetch failed: ${it.message}") }.getOrNull()
    }
}

data class DartListResponse(
    val status: String = "",
    val message: String = "",
    @JsonProperty("page_no") val pageNo: Int = 1,
    @JsonProperty("page_count") val pageCount: Int = 100,
    @JsonProperty("total_count") val totalCount: Int = 0,
    @JsonProperty("total_page") val totalPage: Int = 1,
    val list: List<DartListItem> = emptyList(),
)

data class DartListItem(
    @JsonProperty("corp_code") val corpCode: String = "",
    @JsonProperty("corp_name") val corpName: String = "",
    @JsonProperty("stock_code") val stockCode: String = "",
    @JsonProperty("corp_cls") val corpCls: String = "",
    @JsonProperty("report_nm") val reportNm: String = "",
    @JsonProperty("rcept_no") val rceptNo: String = "",
    @JsonProperty("flr_nm") val flrNm: String = "",
    @JsonProperty("rcept_dt") val rceptDt: String = "",
    val rm: String = "",
) {
    fun toDisclosure() = Disclosure(
        rceptNo = rceptNo, corpCode = corpCode, corpName = corpName,
        stockCode = stockCode, reportNm = reportNm, rceptDt = rceptDt, flrNm = flrNm,
    )
}
