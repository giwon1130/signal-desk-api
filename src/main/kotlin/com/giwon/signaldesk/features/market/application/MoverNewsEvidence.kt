package com.giwon.signaldesk.features.market.application

import java.net.URI
import java.time.Duration
import java.time.Instant

/** 검색 적중은 인과관계가 아니다. 검증된 최근 제목만 관련 보도로 인용한다. */
internal object MoverNewsEvidence {
    const val UNKNOWN_CAUSE = StockMoveContextBuilder.NO_CATALYST

    fun latest(target: MoverReasonTarget, news: List<MarketNews>, now: Instant): MarketNews? = relevant(target, news, now).firstOrNull()

    fun relevant(target: MoverReasonTarget, news: List<MarketNews>, now: Instant): List<MarketNews> = news
        .asSequence()
        .filter { it.market == target.market && it.source.isNotBlank() }
        .filter { item ->
            val published = runCatching { Instant.parse(item.publishedAt) }.getOrNull() ?: return@filter false
            published <= now && Duration.between(published, now) <= Duration.ofHours(96)
        }
        .filter { item ->
            runCatching { URI(item.url).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null } }
                .getOrDefault(false)
        }
        .filter { item ->
            val title = headline(item)
            // 제목을 자르면 뒤의 부정/정정 표현이 사라질 수 있어 긴 제목은 제외한다.
            title.length in 5..220 && FINANCIAL_CONTEXT.containsMatchIn(title) && identifies(target, title)
        }
        .distinctBy { it.url }
        .distinctBy { headline(it).replace(Regex("[\\s\\p{P}]"), "").lowercase() }
        // Corrections and denials stay ahead of older positive reports. No polarity is inferred.
        .sortedWith(compareByDescending<MarketNews> { CORRECTION.containsMatchIn(headline(it)) }
            .thenByDescending { EVENT.containsMatchIn(headline(it)) }.thenByDescending { Instant.parse(it.publishedAt) })
        .toList()

    fun describe(item: MarketNews?): String = if (item == null) UNKNOWN_CAUSE else
        "관련 보도(${item.source.trim()}): 「${headline(item)}」"

    fun headline(item: MarketNews): String = item.title.trim().removeSuffix(" - ${item.source.trim()}").trim()

    private fun identifies(target: MoverReasonTarget, title: String): Boolean {
        val name = target.name.trim()
        val ticker = Regex.escape(target.ticker.trim())
        // 짧은 영문 티커(ON, IT 등)는 일반 단어와 겹친다. $티커/거래소/괄호 표기만 허용한다.
        val explicitTicker = Regex("(?:\\$$ticker(?![A-Za-z0-9])|(?:NASDAQ|NYSE|KRX)\\s*:\\s*$ticker(?![A-Za-z0-9])|\\($ticker\\))", RegexOption.IGNORE_CASE)
        if (explicitTicker.containsMatchIn(title)) return true
        if (name.length < 2 || name.equals(target.ticker, ignoreCase = true)) return false
        val suffix = if (name.any { it in '가'..'힣' }) "(?:은|는|이|가|의|을|를|와|과|도|에서|에)?" else ""
        val names = listOf(name, name.replace(Regex("(?i),?\\s+(inc\\.?|corp\\.?|corporation|incorporated|plc|ltd\\.?)$"), "")).distinct()
        return names.filter { it.length >= 2 && !it.equals(target.ticker, true) }.any { alias ->
            val pattern = alias.split(Regex("\\s+")).joinToString("\\s*") { Regex.escape(it) }
            Regex("(?<![\\p{L}\\p{N}])$pattern(?=$suffix(?:[^\\p{L}\\p{N}]|$))", RegexOption.IGNORE_CASE).containsMatchIn(title)
        }
    }

    private val FINANCIAL_CONTEXT = Regex(
        "주가|증시|주식|공시|실적|매출|영업이익|계약|수주|증자|합병|인수|배당|" +
            "\\b(stock|shares?|earnings|revenue|profit|dividend|acquisition|merger|guidance|buyback|SEC|FDA)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val EVENT = Regex("공시|실적|계약|수주|증자|합병|인수|배당|소각|승인|\\b(earnings|guidance|merger|acquisition|dividend|buyback|FDA)\\b", RegexOption.IGNORE_CASE)
    private val CORRECTION = Regex("정정|사실.*아니|사실.*아냐|부인|철회|취소|\\b(denies|denial|correction|withdraws|cancels)\\b", RegexOption.IGNORE_CASE)
}
