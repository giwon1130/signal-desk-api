package com.giwon.signaldesk.features.workspace.application

import org.flywaydb.core.Flyway
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

@Configuration
@Conditional(JdbcStoreCondition::class)
@EnableConfigurationProperties(SignalDeskJdbcProperties::class)
class SignalDeskJdbcConfig {

    @Bean
    fun signalDeskDataSource(properties: SignalDeskJdbcProperties): DataSource {
        val dataSource = DriverManagerDataSource()
        dataSource.setDriverClassName(properties.driverClassName)
        dataSource.url = properties.resolveJdbcUrl()
        dataSource.username = properties.username
        dataSource.password = properties.password
        return dataSource
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
            return if (url.startsWith("jdbc:")) {
                url
            } else {
                "jdbc:$url"
            }
        }

        require(host.isNotBlank() && database.isNotBlank()) {
            "JDBC workspace store requires either signal-desk.store.jdbc.url or host/database settings"
        }

        return "jdbc:postgresql://$host:$port/$database"
    }
}
