package com.giwon.signaldesk.features.market.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MoverNewsEvidenceTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val target = MoverReasonTarget("KR", "005930", "삼성전자", 5.2)
    private val article = MarketNews("KR", "삼성전자, 신규 공급 계약 공시", "테스트뉴스",
        "https://example.com/news/1", "", now.minusSeconds(3600).toString())

    @Test fun `no news never invents a cause`() {
        assertThat(MoverNewsEvidence.describe(MoverNewsEvidence.latest(target, emptyList(), now)))
            .isEqualTo(StockMoveContextBuilder.NO_CATALYST)
    }

    @Test fun `reject stale future undated unrelated and unattributed search results`() {
        val rejected = listOf(
            article.copy(publishedAt = now.minusSeconds(4 * 86400 + 1).toString()),
            article.copy(publishedAt = now.plusSeconds(1).toString()),
            article.copy(publishedAt = null),
            article.copy(publishedAt = "invalid"),
            article.copy(title = "SK하이닉스 신규 공급 계약 공시"),
            article.copy(title = "삼성전자우 주가 강세"),
            article.copy(title = "다른 기업 주가 강세 - 삼성전자", source = "삼성전자"),
            article.copy(market = "US"),
            article.copy(source = ""),
            article.copy(url = "javascript:alert(1)"),
            article.copy(url = "https://user:secret@example.com/news"),
        )
        rejected.forEach { candidate ->
            assertThat(MoverNewsEvidence.latest(target, listOf(candidate), now)).describedAs(candidate.toString()).isNull()
        }
    }

    @Test fun `cite a recent matched headline without treating it as price causation`() {
        val result = MoverNewsEvidence.describe(MoverNewsEvidence.latest(target, listOf(article), now))
        assertThat(result).doesNotContain("원인은 확인되지")
            .contains("관련 보도(테스트뉴스)", "「삼성전자, 신규 공급 계약 공시」")
            .doesNotContain("때문", "영향으로", "모멘텀", "추정")
        assertThat(MoverNewsEvidence.latest(target, listOf(article.copy(title = "삼성전자가 신규 계약을 공시했습니다")), now)).isNotNull()
    }

    @Test fun `preserve denial and correction instead of truncating it into a positive claim`() {
        val denial = article.copy(title = "삼성전자, 인수 계약 보도 사실 아냐 - 테스트뉴스")
        assertThat(MoverNewsEvidence.describe(MoverNewsEvidence.latest(target, listOf(denial), now)))
            .contains("인수 계약 보도 사실 아냐」")
        val longDenial = article.copy(title = "삼성전자 주가 " + "매우 긴 보도 내용 ".repeat(30) + "사실 아냐")
        assertThat(MoverNewsEvidence.latest(target, listOf(longDenial), now)).isNull()
    }

    @Test fun `weekend reports event headlines and corporate suffix aliases are retained`() {
        val friday = article.copy(publishedAt = now.minusSeconds(3 * 86400).toString())
        val priceOnly = article.copy(title = "삼성전자 주가 5퍼센트 상승", url = "https://example.com/2", publishedAt = now.toString())
        assertThat(MoverNewsEvidence.latest(target, listOf(priceOnly, friday), now)).isEqualTo(friday)
        val us = MoverReasonTarget("US", "AAPL", "Apple Inc.", 5.0)
        val earnings = article.copy(market = "US", title = "Apple earnings released")
        assertThat(MoverNewsEvidence.latest(us, listOf(earnings), now)).isEqualTo(earnings)
    }

    @Test fun `denial remains visible ahead of an earlier deal headline`() {
        val denial = article.copy(title = "삼성전자, 인수 계약 보도 사실 아냐", url = "https://example.com/denial", publishedAt = now.toString())
        assertThat(MoverNewsEvidence.relevant(target, listOf(article, denial), now).first()).isEqualTo(denial)
    }

    @Test fun `short English ticker needs explicit stock identification`() {
        val us = MoverReasonTarget("US", "ON", "ON", 5.1)
        assertThat(MoverNewsEvidence.latest(us, listOf(article.copy(market = "US", title = "Stocks rise on strong revenue")), now)).isNull()
        for (title in listOf("NASDAQ:ON shares rise", "\$ON shares rise", "Company (ON) earnings released")) {
            assertThat(MoverNewsEvidence.latest(us, listOf(article.copy(market = "US", title = title)), now))
                .describedAs(title).isNotNull()
        }
        assertThat(MoverNewsEvidence.latest(us, listOf(article.copy(market = "US", title = "NASDAQ:ONX shares rise")), now)).isNull()
    }
}
