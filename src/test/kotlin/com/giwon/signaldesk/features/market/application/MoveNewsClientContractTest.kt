package com.giwon.signaldesk.features.market.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class MoveNewsClientContractTest {
    private val target = MoverReasonTarget("KR", "005930", "삼성전자", 5.0)

    @Test fun `naver request uses credential headers and response preserves denial and original url`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var query = ""
        var id = ""
        server.createContext("/news") { exchange ->
            query = exchange.requestURI.rawQuery
            id = exchange.requestHeaders.getFirst("X-Naver-Client-Id")
            val bytes = """{"items":[{"title":"<b>삼성전자</b>, 인수 계약 사실 아냐","originallink":"https://example.com/original","link":"https://news.naver.com/1","pubDate":"Tue, 06 Oct 2026 10:00:00 +0900"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = NaverNewsSearchClient(jacksonObjectMapper(), true, "test-id", "test-secret", "http://127.0.0.1:${server.address.port}/news")
            val result = client.search(target)
            assertThat(result.status).isEqualTo("SUCCESS")
            assertThat(result.items.single().title).isEqualTo("삼성전자, 인수 계약 사실 아냐")
            assertThat(result.items.single().url).isEqualTo("https://example.com/original")
            assertThat(result.items.single().publishedAt).isEqualTo("2026-10-06T01:00:00Z")
            assertThat(query).contains("display=30", "sort=date").doesNotContain("test-secret", "test-id")
            assertThat(id).isEqualTo("test-id")
        } finally { server.stop(0) }
    }

    @Test fun `disabled missing key and missing items never masquerade as a successful search`() {
        val client = NaverNewsSearchClient(jacksonObjectMapper(), true, "", "", "http://127.0.0.1:9")
        assertThat(client.search(target).status).isEqualTo("DISABLED")
        assertThat(client.decode("{} ").status).isEqualTo("UNAVAILABLE")
        assertThat(client.decode("{\"items\":[]}").status).isEqualTo("SUCCESS")
    }

    @Test fun `rss distinguishes empty feed from upstream outage and non RSS`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newFixedThreadPool(1)
        var status = 200
        var body = "<rss><channel/></rss>"
        server.createContext("/rss") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = GoogleNewsRssClient(true, "http://127.0.0.1:${server.address.port}/rss", 15, executor)
            assertThat(client.searchWithStatus("KR", "test").status).isEqualTo("SUCCESS")
            status = 429
            assertThat(client.searchWithStatus("KR", "test").status).isEqualTo("UNAVAILABLE")
            status = 200; body = "<html>rate limited</html>"
            assertThat(client.searchWithStatus("KR", "test").status).isEqualTo("UNAVAILABLE")
            body = "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM 'file:///not-readable'> ]><rss><channel>&xxe;</channel></rss>"
            assertThat(client.searchWithStatus("KR", "test").status).isEqualTo("UNAVAILABLE")
        } finally { server.stop(0); executor.shutdownNow() }
    }
}
