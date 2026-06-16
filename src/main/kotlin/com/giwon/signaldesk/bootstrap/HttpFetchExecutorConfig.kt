package com.giwon.signaldesk.bootstrap

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 블로킹 외부 HTTP fetch 용 공유 스레드풀.
 *
 * 시세/뉴스/수급 클라이언트가 `CompletableFuture.supplyAsync { ... }` 를 executor 없이 쓰면
 * JVM 공용 풀(ForkJoinPool.commonPool, 병렬도≈코어-1 → 1-vCPU 호스트에선 사실상 1)에서 돌아
 * 4~8초 블로킹 작업들이 공용 풀을 포화시켜 무관한 작업까지 정체시킨다.
 * 전용 바운드 데몬 풀로 분리해 격리한다. (MarketOverviewService 의 coreFetchPool 과 동일 취지.)
 */
@Configuration
class HttpFetchExecutorConfig {
    @Bean(name = ["httpFetchExecutor"], destroyMethod = "shutdown")
    fun httpFetchExecutor(): ExecutorService =
        Executors.newFixedThreadPool(12) { r ->
            Thread(r, "http-fetch").apply { isDaemon = true }
        }
}
