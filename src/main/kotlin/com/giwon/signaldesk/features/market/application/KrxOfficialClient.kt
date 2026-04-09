package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class KrxOfficialClient(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.integrations.krx.enabled:true}") private val enabled: Boolean,
    @Value("\${signal-desk.integrations.krx.base-url:https://data.krx.co.kr}") private val baseUrl: String,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun loadKoreaMarketSection(): MarketSection? {
        if (!enabled) return null

        return runCatching {
            val indices = fetchJson(
                "/comm/bldAttendant/getJsonData.cmd",
                mapOf("bld" to "dbms/MDC/MAIN/MDCMAIN00101")
            )["output"]

            val marketTrend = fetchJson(
                "/comm/bldAttendant/getJsonData.cmd?bld=dbms/MDC/MAIN/MDCMAIN00105_en"
            )["output"]

            val kospiFlows = fetchJson(
                "/comm/bldAttendant/getJsonData.cmd",
                mapOf(
                    "bld" to "dbms/MDC/MAIN/MDCMAIN00103_en",
                    "mktId" to "STK"
                )
            )["output"]

            val kosdaqFlows = fetchJson(
                "/comm/bldAttendant/getJsonData.cmd",
                mapOf(
                    "bld" to "dbms/MDC/MAIN/MDCMAIN00103_en",
                    "mktId" to "KSQ"
                )
            )["output"]

            val kospi = findIndex(indices, "코스피")
            val kosdaq = findIndex(indices, "코스닥")

            MarketSection(
                market = "KR",
                title = "한국 시장",
                indices = listOfNotNull(
                    kospi?.let { index ->
                        IndexMetric(
                            label = "KOSPI",
                            value = parseNumber(index["PRSNT_IDX"].asText()),
                            changeRate = parseNumber(index["IDX_FLUC_RT"].asText()),
                            periods = buildIndexChartPeriods(
                                latest = parseNumber(index["PRSNT_IDX"].asText()),
                                changeRate = parseNumber(index["IDX_FLUC_RT"].asText()),
                                baseSeries = fetchChart(index["IND_TP_CD"].asText(), index["IDX_IND_CD"].asText())
                            )
                        )
                    },
                    kosdaq?.let { index ->
                        IndexMetric(
                            label = "KOSDAQ",
                            value = parseNumber(index["PRSNT_IDX"].asText()),
                            changeRate = parseNumber(index["IDX_FLUC_RT"].asText()),
                            periods = buildIndexChartPeriods(
                                latest = parseNumber(index["PRSNT_IDX"].asText()),
                                changeRate = parseNumber(index["IDX_FLUC_RT"].asText()),
                                baseSeries = fetchChart(index["IND_TP_CD"].asText(), index["IDX_IND_CD"].asText())
                            )
                        )
                    }
                ),
                sentiment = buildSentiment(kospi, kosdaq, marketTrend),
                investorFlows = mergeInvestorFlows(kospiFlows, kosdaqFlows),
                leadingStocks = defaultLeadingStocks()
            )
        }.getOrNull()
    }

    private fun fetchChart(indTpCd: String, idxIndCd: String): List<Double> {
        val response = fetchJson(
            "/comm/bldAttendant/getJsonData.cmd?bld=dbms/MDC/MAIN/MDCMAIN00102",
            mapOf(
                "ddTp" to "1D",
                "indTpCd" to indTpCd,
                "idxIndCd" to idxIndCd
            )
        )["output"]

        val values = response.toList()
            .mapNotNull { item ->
                item["CLSPRC_IDX"]
                    ?.asText()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::parseNumber)
            }
            .takeLast(12)

        return if (values.isNotEmpty()) values else listOf()
    }

    private fun buildSentiment(
        kospi: JsonNode?,
        kosdaq: JsonNode?,
        marketTrend: JsonNode,
    ): List<SentimentMetric> {
        val kospiChange = kospi?.get("IDX_FLUC_RT")?.asText()?.let(::parseNumber) ?: 0.0
        val kosdaqChange = kosdaq?.get("IDX_FLUC_RT")?.asText()?.let(::parseNumber) ?: 0.0
        val kospiTrend = marketTrend.firstOrNull { it["MKT_NM"]?.asText() == "KOSPI" }
        val kosdaqTrend = marketTrend.firstOrNull { it["MKT_NM"]?.asText() == "KOSDAQ" }

        val kospiTradeValue = kospiTrend?.get("ACC_TRDVAL")?.asText()?.let(::parseNumber) ?: 0.0
        val kosdaqTradeValue = kosdaqTrend?.get("ACC_TRDVAL")?.asText()?.let(::parseNumber) ?: 0.0

        val volatilityScore = (50 + (koreaVolatilityBase(kospiChange, kosdaqChange))).coerceIn(0.0, 100.0)
        val preferenceScore = (50 + ((kospiTradeValue - kosdaqTradeValue) / 400)).coerceIn(0.0, 100.0)
        val chaseRiskScore = (40 + (koreaChaseRiskBase(kospiChange, kosdaqChange))).coerceIn(0.0, 100.0)

        return listOf(
            SentimentMetric(
                label = "변동성",
                state = if (volatilityScore >= 60) "높음" else if (volatilityScore >= 45) "보통" else "낮음",
                score = volatilityScore.toInt(),
                note = "코스피/코스닥 실시간 등락률을 기준으로 계산한 장중 변동성 체감값"
            ),
            SentimentMetric(
                label = "매수심리",
                state = if (preferenceScore >= 55) "코스피 우위" else "균형",
                score = preferenceScore.toInt(),
                note = "시장별 거래대금 기준으로 대형주 선호가 강한지 보는 값"
            ),
            SentimentMetric(
                label = "추격위험",
                state = if (chaseRiskScore >= 60) "주의" else "중간",
                score = chaseRiskScore.toInt(),
                note = "지수 급등락 폭을 바탕으로 장초반 추격 위험을 계산한 값"
            )
        )
    }

    private fun mergeInvestorFlows(
        kospiFlows: JsonNode,
        kosdaqFlows: JsonNode,
    ): List<InvestorFlow> {
        val kospiMap = kospiFlows.associateBy { it["INVST_TP"].asText() }
        val kosdaqMap = kosdaqFlows.associateBy { it["INVST_TP"].asText() }

        return listOf("Individuals", "Foreign", "Institution").map { investor ->
            val kospi = kospiMap[investor]?.get("NETBID_TRDVAL")?.asText()?.let(::parseNumber) ?: 0.0
            val kosdaq = kosdaqMap[investor]?.get("NETBID_TRDVAL")?.asText()?.let(::parseNumber) ?: 0.0
            val total = kospi + kosdaq

            InvestorFlow(
                investor = when (investor) {
                    "Individuals" -> "개인"
                    "Foreign" -> "외국인"
                    else -> "기관"
                },
                amountBillionWon = total,
                note = "KOSPI ${signed(kospi)} / KOSDAQ ${signed(kosdaq)}",
                positive = total >= 0
            )
        }
    }

    private fun defaultLeadingStocks(): List<TickerSnapshot> {
        return listOf(
            TickerSnapshot("005930", "삼성전자", "반도체", 84200, 1.44, "관심 유지"),
            TickerSnapshot("000660", "SK하이닉스", "반도체", 201500, 2.11, "강한 흐름"),
            TickerSnapshot("035420", "NAVER", "플랫폼", 184300, -0.62, "눌림 체크"),
            TickerSnapshot("068270", "셀트리온", "바이오", 176200, -1.15, "주의"),
            TickerSnapshot("005380", "현대차", "자동차", 248500, 0.93, "추세 유지"),
            TickerSnapshot("105560", "KB금융", "금융", 78100, 1.21, "수급 양호")
        )
    }

    private fun findIndex(indices: JsonNode, keyword: String): JsonNode? {
        return indices.firstOrNull { node -> node["IDX_IND_NM"]?.asText()?.contains(keyword) == true }
    }

    private fun fetchJson(path: String, formData: Map<String, String> = emptyMap()): JsonNode {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(5))
            .header("Referer", "$baseUrl/contents/MDC/MAIN/main/index.cmd?locale=en")
            .header("X-Requested-With", "XMLHttpRequest")

        val request = if (formData.isEmpty()) {
            requestBuilder.GET().build()
        } else {
            requestBuilder
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(formData.entries.joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value)}"
                }))
                .build()
        }

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return objectMapper.readTree(response.body())
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun parseNumber(raw: String): Double {
        return raw.replace(",", "").toDoubleOrNull() ?: 0.0
    }

    private fun signed(value: Double): String {
        return "${if (value > 0) "+" else ""}${value.toInt()}억"
    }

    private fun koreaVolatilityBase(kospi: Double, kosdaq: Double): Double {
        return (kotlin.math.abs(kospi) * 4) + (kotlin.math.abs(kosdaq) * 6)
    }

    private fun koreaChaseRiskBase(kospi: Double, kosdaq: Double): Double {
        return (kotlin.math.abs(kospi) * 5) + (kotlin.math.abs(kosdaq) * 7)
    }
}
