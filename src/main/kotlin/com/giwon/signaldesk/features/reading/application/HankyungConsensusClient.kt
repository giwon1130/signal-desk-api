package com.giwon.signaldesk.features.reading.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 한경 컨센서스(공개 증권사 리포트) 스크레이퍼.
 * http://consensus.hankyung.com/analysis/list — 서버 렌더 테이블에서 종목·목표가·투자의견·증권사 추출.
 *
 * 행 구조(td 순서): 날짜 / 제목(종목명(코드)) / 목표가 / 투자의견 / 작성자 / 증권사 / ...버튼.
 */
@Component
class HankyungConsensusClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    data class ReportCall(
        val reportIdx: String,
        val date: String,
        val name: String,
        val ticker: String,      // 6자리 코드
        val targetPrice: Int,    // 목표가(원). 없으면 0
        val opinion: String,     // Buy/Hold/매수 등
        val firm: String,        // 증권사
    )

    private val rowRe = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val cellRe = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val reportIdxRe = Regex("report_idx=(\\d+)")
    private val nameCodeRe = Regex("([^<>(]+)\\((\\d{6})\\)")
    private val tagRe = Regex("<[^>]+>")

    /** 최신 리포트 목록(상단=최신). 실패 시 빈 리스트. */
    fun fetchRecent(): List<ReportCall> {
        val html = runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://consensus.hankyung.com/analysis/list?skinType=business"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) { log.warn("Hankyung consensus HTTP {}", res.statusCode()); null }
            else res.body()
        }.getOrNull() ?: return emptyList()
        return parse(html)
    }

    /** 리스트 HTML → ReportCall (테스트용 분리). */
    fun parse(html: String): List<ReportCall> =
        rowRe.findAll(html).mapNotNull { m ->
            val row = m.groupValues[1]
            val cells = cellRe.findAll(row).map { tagRe.replace(it.groupValues[1], "").trim() }.toList()
            if (cells.size < 6) return@mapNotNull null
            val reportIdx = reportIdxRe.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val nc = nameCodeRe.find(cells[1]) ?: return@mapNotNull null
            val name = nc.groupValues[1].trim()
            val code = nc.groupValues[2]
            val target = cells[2].replace(",", "").toIntOrNull() ?: 0
            ReportCall(
                reportIdx = reportIdx,
                date = cells[0].trim(),
                name = name,
                ticker = code,
                targetPrice = target,
                opinion = cells[3].trim(),
                firm = cells[5].trim(),
            )
        }.toList()
}
