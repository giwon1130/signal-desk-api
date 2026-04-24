package com.giwon.signaldesk.features.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.giwon.signaldesk.features.market.application.NaverFinanceQuoteClient
import com.giwon.signaldesk.features.market.application.NaverGlobalQuoteClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * 실시간 시세 WebSocket.
 *
 * 클라이언트 → 서버 (text frame, JSON):
 *   { "action":"subscribe",   "tickers":["005930","NVDA"] }
 *   { "action":"unsubscribe", "tickers":["005930"] }
 *
 * 서버 → 클라이언트 (브로드캐스트):
 *   { "type":"price",   "ticker":"005930", "price":71200, "changeRate":1.23, "ts":1735200000000 }
 *   { "type":"snapshot","prices":{ "005930": {...}, ... } }
 */
@Component
class PriceTickerHandler(
    private val mapper: ObjectMapper,
    private val naver: NaverFinanceQuoteClient,
    private val naverGlobal: NaverGlobalQuoteClient,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** session → 구독 중인 ticker 집합 */
    private val subscriptions = ConcurrentHashMap<String, MutableSet<String>>()
    /** sessionId → session */
    private val sessions      = ConcurrentHashMap<String, WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions[session.id] = session
        subscriptions[session.id] = ConcurrentHashMap.newKeySet()
        log.info("WS opened {} (total={})", session.id, sessions.size)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessions.remove(session.id)
        subscriptions.remove(session.id)
        log.info("WS closed {} reason={} (total={})", session.id, status, sessions.size)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val node = runCatching { mapper.readTree(message.payload) }.getOrNull() ?: return
        val action = node.get("action")?.asText().orEmpty()
        val tickers = node.get("tickers")?.mapNotNull { it.asText().takeIf { s -> s.isNotBlank() } }.orEmpty()
        if (tickers.isEmpty()) return
        val subs = subscriptions[session.id] ?: return
        when (action) {
            "subscribe"   -> subs.addAll(tickers)
            "unsubscribe" -> subs.removeAll(tickers.toSet())
        }
    }

    /**
     * 5초마다 모든 구독 ticker 가격을 한 번에 갱신해 broadcast.
     *
     * 티커 분류:
     *  - 6자리 숫자 → 한국 (NaverFinanceQuoteClient, polling 엔드포인트로 벌크 조회)
     *  - 그 외 (알파벳/숫자 조합) → 미국 (NaverGlobalQuoteClient, 종목별 병렬 호출)
     *
     * US 가격은 원래 Double 인데 StockQuote.currentPrice 가 Int 라서 1원 단위로 반올림돼서
     * 나간다 (예: AAPL 204.15 → 204). 도메인이 대부분 KR 이므로 MVP 수준에서 수용 가능.
     */
    @Scheduled(fixedDelay = 5_000L, initialDelay = 5_000L)
    fun broadcastPrices() {
        if (sessions.isEmpty()) return
        val allTickers = subscriptions.values.flatten().toSet()
        if (allTickers.isEmpty()) return

        val krTickers = allTickers.filter { it.matches(Regex("\\d{6}")) }
        val usTickers = allTickers.filter { it.matches(Regex("[A-Za-z][A-Za-z0-9.-]*")) }

        val quotes = mutableMapOf<String, com.giwon.signaldesk.features.market.application.StockQuote>()
        if (krTickers.isNotEmpty()) {
            quotes += runCatching { naver.fetchKoreanQuotes(krTickers) }.getOrDefault(emptyMap())
        }
        if (usTickers.isNotEmpty()) {
            quotes += runCatching { naverGlobal.fetchUsQuotes(usTickers) }.getOrDefault(emptyMap())
        }
        if (quotes.isEmpty()) return

        val now = System.currentTimeMillis()

        sessions.forEach { (sid, session) ->
            val subs = subscriptions[sid] ?: return@forEach
            if (!session.isOpen) return@forEach
            val payload = quotes
                .filterKeys { it in subs }
                .mapValues { (_, q) ->
                    mapOf(
                        "type" to "price",
                        "ticker" to q.ticker,
                        "price" to q.currentPrice,
                        "changeRate" to q.changeRate,
                        "ts" to now,
                    )
                }
            if (payload.isEmpty()) return@forEach
            val frame = mapper.writeValueAsString(mapOf("type" to "snapshot", "prices" to payload))
            runCatching { session.sendMessage(TextMessage(frame)) }
        }
    }
}

@Configuration
@EnableWebSocket
class WebSocketConfig(private val handler: PriceTickerHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/prices")
            .setAllowedOriginPatterns("*")
    }
}
