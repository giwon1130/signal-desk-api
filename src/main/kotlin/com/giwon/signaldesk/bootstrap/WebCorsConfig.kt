package com.giwon.signaldesk.bootstrap

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebCorsConfig(
    @Value("\${signal-desk.cors.allowed-origin-patterns:}") private val configuredAllowedOriginPatterns: String,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        val allowedOriginPatterns = buildList {
            add("http://localhost:*")
            add("http://127.0.0.1:*")
            add("https://localhost:*")
            add("https://127.0.0.1:*")
            configuredAllowedOriginPatterns
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }

        registry.addMapping("/**")
            .allowedOriginPatterns(*allowedOriginPatterns.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600)
    }
}
