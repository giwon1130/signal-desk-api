package com.giwon.signaldesk.features.workspace.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
@EnableConfigurationProperties(SignalDeskJdbcProperties::class)
class SignalDeskJdbcConfig {

    @Bean
    fun signalDeskDataSource(properties: SignalDeskJdbcProperties): DataSource {
        val dataSource = DriverManagerDataSource()
        dataSource.setDriverClassName(properties.driverClassName)
        dataSource.url = properties.url
        dataSource.username = properties.username
        dataSource.password = properties.password
        return dataSource
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
)
