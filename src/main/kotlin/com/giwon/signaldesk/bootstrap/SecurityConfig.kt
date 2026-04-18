package com.giwon.signaldesk.bootstrap

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { } // WebCorsConfig가 처리
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // 공개 엔드포인트
                    .requestMatchers(
                        "/health",
                        "/auth/**",
                        "/api/v1/market/**",          // 시장 데이터는 공개
                        "/api/v1/kakao/**",           // 카카오 챗봇 웹훅
                        "/ws/**",                     // 웹소켓 (별도 검증)
                    ).permitAll()
                    // 그 외 모두 (워크스페이스 등): 컨트롤러 단에서 JWT 검증
                    .anyRequest().permitAll()
            }
        return http.build()
    }
}
