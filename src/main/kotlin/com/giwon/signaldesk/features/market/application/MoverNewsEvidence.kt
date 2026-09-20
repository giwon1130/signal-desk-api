package com.giwon.signaldesk.features.market.application

import java.net.URI
import java.time.Duration
import java.time.Instant

/** 검색 적중은 인과관계가 아니다. 검증된 최근 제목만 관련 보도로 인용한다. */
internal object MoverNewsEvidence {
    const val UNKNOWN_CAUSE = "주가 변동의 원인은 확인되지 않았습니다."

    fun latest(target: MoverReasonTarget, news: List<MarketNews>, now: Instant): MarketNews? = news
        .asSequence()
        .filter { it.market == target.market && it.source.isNotBlank() }
        .filter { item ->
            val published = runCatching { Instant.parse(item.publishedAt) }.getOrNull() ?: return@filter false
            published <= now && Duration.between(published, now) <= Duration.ofHours(24)
        }
        .filter { item ->
            runCatching { URI(item.url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null } }
                .getOrDefault(false)
        }
        .filter { item ->
            val title = headline(item)
            // 제목을 자르면 뒤의 부정/정정 표현이 사라질 수 있어 긴 제목은 제외한다.
            title.length in 5..100 && FINANCIAL_CONTEXT.containsMatchIn(title) && identifies(target, title)
        }
        .distinctBy { it.url }
        .maxByOrNull { Instant.parse(it.publishedAt) }

    fun describe(item: MarketNews?): String = if (item == null) UNKNOWN_CAUSE else
        "$UNKNOWN_CAUSE 관련 보도(${item.source.trim()}): 「${headline(item)}」"

    private fun headline(item: MarketNews): String = item.title.trim().removeSuffix(" - ${item.source.trim()}").trim()

    private fun identifies(target: MoverReasonTarget, title: String): Boolean {
        val name = target.name.trim()
        val ticker = Regex.escape(target.ticker.trim())
        // 짧은 영문 티커(ON, IT 등)는 일반 단어와 겹친다. $티커/거래소/괄호 표기만 허용한다.
        val explicitTicker = Regex("(?:\\$$ticker(?![A-Za-z0-9])|(?:NASDAQ|NYSE|KRX)\\s*:\\s*$ticker(?![A-Za-z0-9])|\\($ticker\\))", RegexOption.IGNORE_CASE)
        if (explicitTicker.containsMatchIn(title)) return true
        if (name.length < 2 || name.equals(target.ticker, ignoreCase = true)) return false
        val suffix = if (name.any { it in '가'..'힣' }) "(?:은|는|이|가|의|을|를|와|과|도|에서|에)?" else ""
        return Regex("(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?=$suffix(?:[^\\p{L}\\p{N}]|$))", RegexOption.IGNORE_CASE)
            .containsMatchIn(title)
    }

    private val FINANCIAL_CONTEXT = Regex(
        "주가|증시|주식|공시|실적|매출|영업이익|계약|수주|증자|합병|인수|배당|" +
            "\\b(stock|shares?|earnings|revenue|profit|dividend|acquisition|merger|guidance|buyback|SEC|FDA)\\b",
        RegexOption.IGNORE_CASE,
    )
}
