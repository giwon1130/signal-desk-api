package com.giwon.signaldesk.features.kakao

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class KakaoAlertStore(
    private val objectMapper: ObjectMapper,
    @Value("\${signal-desk.store.path:./data/signal-desk-store.json}") storePath: String,
) {
    private val alertPath: Path = Path.of(storePath).resolveSibling("kakao-alert.json")
        .toAbsolutePath().normalize()
    private val lock = Any()

    @PostConstruct
    fun init() {
        Files.createDirectories(alertPath.parent)
    }

    fun save(result: DailyAlertResult) = synchronized(lock) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(alertPath.toFile(), result)
    }

    fun load(): DailyAlertResult? = synchronized(lock) {
        if (!Files.exists(alertPath)) return null
        runCatching { objectMapper.readValue<DailyAlertResult>(alertPath.toFile()) }.getOrNull()
    }
}
