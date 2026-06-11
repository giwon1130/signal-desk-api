package com.giwon.signaldesk.bootstrap

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/**
 * IP 기반 in-memory rate limiter (Bucket4j).
 *
 * 비용 절감 + 봇 트래픽 차단 — Railway 의 외부 API 호출 / compute 폭주 방지.
 *
 * 정책:
 *  - /auth/...   (oauth, login, signup) → 5 req / min / IP   (브루트포스 방지)
 *  - /api/v1/market/...                  → 60 req / min / IP  (정상 사용 +α)
 *  - 그 외                                → 통과 (push device 등록 등 사용 빈도 낮음)
 *
 * IP 식별:
 *  - X-Forwarded-For 마지막 토큰 — 신뢰 가능한 건 Railway proxy 가 "덧붙인" 마지막 항목뿐.
 *    첫 토큰은 클라이언트가 임의 헤더로 위조 가능 → 요청마다 새 버킷을 받아 limit 우회됐었다.
 *  - 없으면 remoteAddr fallback
 *
 * 한계:
 *  - 단일 인스턴스 메모리 — 여러 컨테이너로 scale-out 시 IP 별 limit 이 인스턴스마다 따로
 *    (1 인스턴스 운영 중이라 OK)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RateLimitFilter : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(javaClass)

    // 크기 상한 + 유휴 만료 — 전체 clear() 는 모든 IP 의 limit 을 동시에 리셋해 우회 수단이 됐었다.
    private val buckets = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build<String, Bucket>()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI ?: ""
        val rule = pickRule(path)
        if (rule == null) {
            chain.doFilter(request, response)
            return
        }

        val ip = clientIp(request)
        val key = "${rule.name}:$ip"
        val bucket = buckets.get(key) { rule.newBucket() }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response)
        } else {
            logger.warn("rate-limit ${rule.name} ip=$ip path=$path")
            response.status = 429
            response.contentType = "application/json;charset=UTF-8"
            response.setHeader("Retry-After", "60")
            response.writer.write("""{"success":false,"error":"요청이 너무 많아요. 잠시 후 다시 시도해주세요."}""")
        }
    }

    private fun clientIp(req: HttpServletRequest): String {
        val xff = req.getHeader("X-Forwarded-For")?.split(",")?.lastOrNull()?.trim()
        return if (!xff.isNullOrBlank()) xff else req.remoteAddr ?: "unknown"
    }

    private fun pickRule(path: String): Rule? = when {
        // OAuth / 로그인 / 회원가입 — 브루트포스 보호
        path.startsWith("/auth/") -> AUTH_RULE
        // 시장 데이터 — 정상 사용자 60 req/min 충분
        path.startsWith("/api/v1/market/") -> MARKET_RULE
        else -> null
    }

    private data class Rule(val name: String, val capacity: Long, val refillPerMinute: Long) {
        fun newBucket(): Bucket = Bucket.builder()
            .addLimit(Bandwidth.simple(capacity, Duration.ofMinutes(1)).withInitialTokens(capacity))
            .build()
    }

    companion object {
        private val AUTH_RULE = Rule(name = "auth", capacity = 5, refillPerMinute = 5)
        private val MARKET_RULE = Rule(name = "market", capacity = 60, refillPerMinute = 60)
    }
}
