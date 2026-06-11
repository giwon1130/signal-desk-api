package com.giwon.signaldesk.features.workspace.application

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import javax.sql.DataSource

@Configuration
@Conditional(JdbcStoreCondition::class)
@EnableConfigurationProperties(SignalDeskJdbcProperties::class)
class SignalDeskJdbcConfig {

    @Bean(destroyMethod = "close")
    fun signalDeskDataSource(properties: SignalDeskJdbcProperties): DataSource {
        val config = HikariConfig()
        config.driverClassName = properties.driverClassName
        config.jdbcUrl = properties.resolveJdbcUrl()
        config.username = properties.username
        config.password = properties.password
        // Railway PG 무료 플랜 커넥션 여유가 작다 — 본인 트래픽엔 5면 충분.
        config.maximumPoolSize = 5
        config.minimumIdle = 1
        config.connectionTimeout = 5_000
        config.idleTimeout = 300_000
        return HikariDataSource(config)
    }

    @Bean
    fun signalDeskFlyway(dataSource: DataSource): Flyway {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
        flyway.migrate()
        return flyway
    }

    @Bean
    fun signalDeskJdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
}

@ConfigurationProperties(prefix = "signal-desk.store.jdbc")
data class SignalDeskJdbcProperties(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val driverClassName: String = "org.postgresql.Driver",
    val host: String = "",
    val port: Int = 5432,
    val database: String = "",
) {
    fun resolveJdbcUrl(): String {
        if (url.isNotBlank()) {
            val normalized = if (url.startsWith("jdbc:")) url.removePrefix("jdbc:") else url
            return normalizeJdbcUrl(normalized)
        }

        require(host.isNotBlank() && database.isNotBlank()) {
            "JDBC workspace store requires either signal-desk.store.jdbc.url or host/database settings"
        }

        return "jdbc:postgresql://$host:$port/$database"
    }

    private fun normalizeJdbcUrl(candidate: String): String {
        val parsed = URI(candidate)
        val scheme = when (parsed.scheme?.lowercase()) {
            "postgres", "postgresql" -> "postgresql"
            else -> return "jdbc:$candidate"
        }

        val resolvedHost = parsed.host ?: return "jdbc:$candidate"
        val resolvedPort = if (parsed.port == -1) 5432 else parsed.port
        val resolvedPath = parsed.path?.removePrefix("/").orEmpty()
        val querySuffix = parsed.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()

        return "jdbc:$scheme://$resolvedHost:$resolvedPort/$resolvedPath$querySuffix"
    }
}
