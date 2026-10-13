package com.giwon.signaldesk.features.market.application

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A reported explanation is attributed context, not a verified causal contribution. */
data class MarketDriverSource(val title: String, val source: String, val url: String, val publishedAt: String)
data class MarketDriver(
    val id: String,
    val label: String,
    val summary: String,
    val watchPoint: String,
    val sources: List<MarketDriverSource>,
    val kind: String = "REPORTED_CONTEXT",
    val basis: String = "HEADLINE",
)

/** Pure, replayable rules. Never fetch article bodies, infer flow amounts, or ask an LLM for causes. */
internal object MarketDriverBuilder {
    private data class Rule(val id: String, val label: String, val direction: String,
                            val topic: Regex, val detail: Regex, val summary: String, val watch: String,
                            val conflicts: Regex? = null)
    private fun pattern(value: String) = Regex(value, RegexOption.IGNORE_CASE)
    private val down = pattern("하락|급락|약세|폭락|검은|붕괴|밀려|미끌|\\b(fall|falls|fell|drop|drops|slump|slumps|selloff|sell-off|lower|slide|slides|losses)\\b")
    private val up = pattern("상승|급등|강세|반등|\\b(rise|rises|rose|rally|rallies|gain|gains|higher|surge|surges)\\b")
    private val krMarket = pattern("코스피|코스닥|한국\\s*증시|국내\\s*증시|국장|KOSPI|KOSDAQ")
    private val usMarket = pattern("S&P\\s*500|나스닥|뉴욕\\s*증시|미국\\s*증시|미\\s*증시|월가|Nasdaq|Wall Street|US stocks|U\\.S\\. stocks")
    private val excluded = pattern("정정|부인|사실\\s*(아니|아냐)|루머|전망|예상|내일|내년|다음\\s*주|지난\\s*(주|달|해)|작년|어제|회고|가능성|할까|될까|\\?|\\b(forecast|outlook|rumor|denies|tomorrow|next week|last week|yesterday)\\b")
    private val contradiction = pattern("에도|불구|아닌|아니다|아냐|없다|해소|완화|반전|낙폭\\s*축소|despite|not driven|not due|eases|easing")
    // A same-date futures / extended-hours report can describe the next session, not this cash close.
    private val differentSession = pattern("선물|야간|시간외|시간\\s*외|프리마켓|애프터마켓|장전|개장\\s*전|\\bfutures\\b|after.hours|pre.market|extended.hours")
    private val rules = listOf(
        Rule("foreign_sell", "외국인 매도", "DOWN", pattern("외국인|외인"), pattern("(?:외국인|외인).{0,18}(순매도|매도|팔자|이탈|짐\\s*싸)"),
            "당일 보도에서는 외국인 매도세가 증시 약세의 배경으로 부각됐습니다.", "외국인 매도세가 이어지는지와 대형주의 낙폭을 함께 살펴볼 필요가 있습니다.", pattern("기관|개인|매수|사자")),
        Rule("foreign_buy", "외국인 매수", "UP", pattern("외국인|외인"), pattern("(?:외국인|외인).{0,18}(순매수|매수|사자)"),
            "당일 보도에서는 외국인 매수세가 증시 강세의 배경으로 부각됐습니다.", "외국인 매수세가 이어지는지와 상승 종목이 넓어지는지 확인할 필요가 있습니다.", pattern("기관|개인|매도|팔자")),
        Rule("institution_sell", "기관 매도", "DOWN", pattern("기관"), pattern("기관.{0,12}(순매도|매도|팔자)"),
            "당일 보도에서는 기관의 매도세가 시장 부담으로 거론됐습니다.", "기관 수급과 주요 업종의 움직임을 함께 확인할 필요가 있습니다.", pattern("외국인|외인|개인|매수|사자")),
        Rule("institution_buy", "기관 매수", "UP", pattern("기관"), pattern("기관.{0,12}(순매수|매수|사자)"),
            "당일 보도에서는 기관의 매수세가 시장을 뒷받침한 배경으로 거론됐습니다.", "기관 수급과 주요 업종의 움직임을 함께 확인할 필요가 있습니다.", pattern("외국인|외인|개인|매도|팔자")),
        Rule("semiconductor_weak", "반도체 약세", "DOWN", pattern("반도체|삼성전자|하이닉스|삼전|닉스|semiconductor|chip stocks|chipmakers"),
            pattern("(?:반도체|삼성전자|하이닉스|삼전|닉스|semiconductor|chip stocks|chipmakers).{0,16}(급락|하락|약세|매도|불안|fall|drop|slump|slide|selloff|losses)"),
            "반도체주 약세도 당일 증시 보도에서 주요 부담으로 다뤄졌습니다.", "반도체 대형주의 낙폭과 업종 전반으로 약세가 번지는지 확인할 필요가 있습니다.", pattern("${krMarket.pattern}|${usMarket.pattern}|${up.pattern}")),
        Rule("semiconductor_strong", "반도체 강세", "UP", pattern("반도체|삼성전자|하이닉스|semiconductor|chip stocks|chipmakers"),
            pattern("(?:반도체|삼성전자|하이닉스|semiconductor|chip stocks|chipmakers).{0,16}(강세|상승|급등|rally|gain|surge)"),
            "반도체주 강세가 당일 증시 보도에서 주요 배경으로 다뤄졌습니다.", "반도체 업종의 강세가 이어지는지와 다른 업종으로 확산되는지 확인할 필요가 있습니다.", pattern("${krMarket.pattern}|${usMarket.pattern}|${down.pattern}")),
        Rule("ai_concern", "AI 관련 우려", "DOWN", pattern("AI|인공지능"), pattern("(?:AI|인공지능).{0,20}(거품|수익성\\s*우려|수요\\s*우려|투자\\s*우려|bubble|concern|fears)"),
            "당일 보도에서는 AI 관련 우려도 시장 부담으로 거론됐습니다.", "AI 관련 기업의 실적과 투자 계획에 실제 변화가 있는지 확인할 필요가 있습니다."),
        Rule("rates_rise", "금리 상승 부담", "DOWN", pattern("금리|국채|yield|rates"), pattern("(?:금리|국채|yield|rates).{0,12}(상승|급등|오르|rise|rising|jump|higher|surge)"),
            "당일 보도에서는 금리 상승 부담이 증시 약세의 배경으로 거론됐습니다.", "국채금리의 추가 움직임과 성장주의 반응을 함께 확인할 필요가 있습니다."),
        Rule("earnings_weak", "기업 실적 우려", "DOWN", pattern("실적|가이던스|earnings|guidance"), pattern("실적\\s*(부진|실망|우려)|가이던스\\s*(하향|실망)|earnings miss|weak guidance|profit warning"),
            "당일 보도에서는 기업 실적이나 전망에 대한 실망이 부담으로 거론됐습니다.", "기업이 발표한 실적과 향후 전망이 시장 예상과 얼마나 달랐는지 확인할 필요가 있습니다."),
        Rule("earnings_strong", "기업 실적 호조", "UP", pattern("실적|earnings|profit"), pattern("실적\\s*(호조|개선|서프라이즈)|earnings beat|strong earnings|record profit"),
            "당일 보도에서는 기업 실적 호조가 증시 강세의 배경으로 거론됐습니다.", "실적 개선이 다른 기업과 업종에서도 확인되는지 살펴볼 필요가 있습니다."),
    )

    fun build(observation: MarketObservation, news: List<MarketNews>, now: Instant): List<MarketDriver> {
        if (observation.direction !in setOf("UP", "DOWN") || observation.status == "UNAVAILABLE") return emptyList()
        val date = runCatching { LocalDate.parse(observation.tradingDate) }.getOrNull() ?: return emptyList()
        val zone = ZoneId.of(if (observation.market == "KR") "Asia/Seoul" else "America/New_York")
        val marketPattern = if (observation.market == "KR") krMarket else usMarket
        val candidates = news.asSequence().filter { it.market == observation.market }
            .filter { it.source.isNotBlank() && it.source != "Google News" && safeNewsUrl(it.url) }
            .filter { item -> runCatching { Instant.parse(item.publishedAt).let {
                it <= now && it.atZone(zone).toLocalDate() == date
            } }.getOrDefault(false) }
            .map { it to MoverNewsEvidence.headline(it) }
            .filter { (_, title) -> title.length in 8..220 && marketDirectionMatches(title, marketPattern, observation.direction) &&
                !excluded.containsMatchIn(title) && !contradiction.containsMatchIn(title) && !differentSession.containsMatchIn(title) }
            .distinctBy { canonicalNewsUrl(it.first.url) }
            .distinctBy { it.second.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .sortedByDescending { it.first.publishedAt }.toList()
        val usedArticles = mutableSetOf<String>()
        return rules.filter { it.direction == observation.direction }.mapNotNull { rule ->
            val sources = candidates.filter { (item, title) -> canonicalNewsUrl(item.url) !in usedArticles &&
                rule.topic.containsMatchIn(title) && rule.detail.findAll(title).any { match ->
                    rule.conflicts?.containsMatchIn(match.value) != true
                } }
                .distinctBy { it.first.source.lowercase(Locale.ROOT) }.take(2)
            if (sources.isEmpty()) null else {
                usedArticles += sources.map { canonicalNewsUrl(it.first.url) }
                MarketDriver(rule.id, rule.label, rule.summary, rule.watch, sources.map { (item, title) ->
                    MarketDriverSource(title, item.source, item.url, item.publishedAt!!)
                })
            }
        }.take(3)
    }

    private fun marketDirectionMatches(title: String, market: Regex, expected: String): Boolean {
        // "Nasdaq falls as yields rise" is a falling market, not a mixed market. Associate movement
        // with each named index, not every directional word anywhere in the headline.
        val mentions = market.findAll(title).toList()
        if (mentions.isEmpty()) return false
        val movements = mentions.mapIndexedNotNull { index, match ->
            val end = mentions.getOrNull(index + 1)?.range?.first ?: title.length
            val clause = title.substring(match.range.last + 1, end)
            val falling = down.find(clause)?.range?.first
            val rising = up.find(clause)?.range?.first
            when {
                falling != null && (rising == null || falling < rising) -> "DOWN"
                rising != null -> "UP"
                else -> null
            }
        }
        return movements.isNotEmpty() && movements.all { it == expected }
    }
}

internal fun safeNewsUrl(value: String): Boolean = runCatching {
    URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
}.getOrDefault(false)

/** Keep article IDs in query strings; stripping all queries collapses different articles into one. */
internal fun canonicalNewsUrl(value: String): String = runCatching {
    val uri = URI(value)
    val query = uri.rawQuery?.split('&')?.filterNot { it.startsWith("utm_") || it.startsWith("oc=") }?.joinToString("&")?.ifBlank { null }
    URI(uri.scheme, uri.rawAuthority, uri.rawPath, query, null).toString()
}.getOrDefault(value)
