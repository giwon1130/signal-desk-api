package com.giwon.signaldesk.features.market.application

import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 네이버 finance 매매상위 페이지 스크래핑 — 외인/기관 순매수·매도 상위 종목.
 *  URL: https://finance.naver.com/sise/sise_deal_rank.naver?sosok=01&investor_gubun=9000&type=buy
 *  응답은 EUC-KR 인코딩. 첫 번째 표(`rdr.list`)가 해당 카테고리 순위.
 *
 * 정규식만으로 종목명/코드 페어 추출. 거래대금 같은 부가 정보는 단순화를 위해 생략.
 */
@Component
class NaverInvestorRankClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val eucKr: Charset = Charset.forName("EUC-KR")

    /**
     * @param market "KOSPI" or "KOSDAQ"
     * @param investor "FOREIGN" or "INSTITUTION"
     * @param type "BUY" (순매수) or "SELL" (순매도)
     */
    @Cacheable(cacheNames = ["investor-rank"], unless = "#result.isEmpty()")
    fun fetchTop(market: String, investor: String, type: String, limit: Int = 10): List<InvestorRankItem> {
        val sosok = if (market.equals("KOSDAQ", ignoreCase = true)) "02" else "01"
        val gubun = if (investor.equals("INSTITUTION", ignoreCase = true)) "1000" else "9000"
        val typeParam = if (type.equals("SELL", ignoreCase = true)) "sell" else "buy"
        val url = "https://finance.naver.com/sise/sise_deal_rank.naver?sosok=$sosok&investor_gubun=$gubun&type=$typeParam"
        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://finance.naver.com/")
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() != 200) {
                log.warn("naver investor rank http {}", resp.statusCode())
                return@runCatching emptyList()
            }
            val body = String(resp.body(), eucKr)
            parseFirstTable(body).take(limit)
        }.onFailure { log.warn("naver investor rank fetch failed: ${it.message}") }
            .getOrElse { emptyList() }
    }

    /**
     * 모닝 브리프용 수급 묶음 — KOSPI 외인/기관 순매수·순매도 + KOSDAQ 외인 순매수.
     * 5개 페이지를 병렬 fetch (각각 fetchTop 캐시 활용). 어느 하나 실패해도 빈 리스트로 채워 반환.
     */
    fun fetchFlowSnapshot(limit: Int = 7): InvestorFlowSnapshot {
        val tasks = mapOf(
            "kospiForeignBuy" to Triple("KOSPI", "FOREIGN", "BUY"),
            "kospiForeignSell" to Triple("KOSPI", "FOREIGN", "SELL"),
            "kospiInstitutionBuy" to Triple("KOSPI", "INSTITUTION", "BUY"),
            "kospiInstitutionSell" to Triple("KOSPI", "INSTITUTION", "SELL"),
            "kosdaqForeignBuy" to Triple("KOSDAQ", "FOREIGN", "BUY"),
        ).mapValues { (_, t) ->
            CompletableFuture.supplyAsync {
                runCatching { fetchTop(t.first, t.second, t.third, limit) }.getOrElse { emptyList() }
            }
        }
        return InvestorFlowSnapshot(
            kospiForeignBuy = tasks.getValue("kospiForeignBuy").join(),
            kospiForeignSell = tasks.getValue("kospiForeignSell").join(),
            kospiInstitutionBuy = tasks.getValue("kospiInstitutionBuy").join(),
            kospiInstitutionSell = tasks.getValue("kospiInstitutionSell").join(),
            kosdaqForeignBuy = tasks.getValue("kosdaqForeignBuy").join(),
        )
    }

    private fun parseFirstTable(body: String): List<InvestorRankItem> {
        // 첫 번째 표 항목은 `rdr.list', 'XXXXXX', 'N'` 패턴. 이후 다른 표는 `rpk.list` 등으로 구분됨.
        val pattern = Regex(
            """code=(\d{6})"\s+class="company"\s+onClick="clickcr\(this,\s*'rdr\.list',\s*'\d{6}',\s*'(\d+)',\s*event\)">([^<]+)</a>""",
        )
        return pattern.findAll(body)
            .map { m ->
                InvestorRankItem(
                    ticker = m.groupValues[1],
                    rank = m.groupValues[2].toIntOrNull() ?: 0,
                    name = m.groupValues[3].trim(),
                )
            }
            .toList()
            .sortedBy { it.rank }
    }
}

data class InvestorRankItem(
    val ticker: String,
    val rank: Int,
    val name: String,
)

/** 모닝 브리프 수급 묶음. 각 리스트는 비어있을 수 있음 (스크래핑 실패 허용). */
data class InvestorFlowSnapshot(
    val kospiForeignBuy: List<InvestorRankItem>,
    val kospiForeignSell: List<InvestorRankItem>,
    val kospiInstitutionBuy: List<InvestorRankItem>,
    val kospiInstitutionSell: List<InvestorRankItem>,
    val kosdaqForeignBuy: List<InvestorRankItem>,
) {
    fun isEmpty(): Boolean =
        kospiForeignBuy.isEmpty() && kospiForeignSell.isEmpty() &&
            kospiInstitutionBuy.isEmpty() && kospiInstitutionSell.isEmpty() &&
            kosdaqForeignBuy.isEmpty()
}
