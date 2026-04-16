package com.giwon.signaldesk.features.workspace.application

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

class JdbcStoreCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val environment = context.environment
        val explicitMode = environment.getProperty("signal-desk.store.mode")?.trim()?.lowercase().orEmpty()

        return when {
            explicitMode == "jdbc" -> true
            explicitMode == "file" -> false
            else -> hasJdbcSettings(environment)
        }
    }
}

class FileStoreCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val environment = context.environment
        val explicitMode = environment.getProperty("signal-desk.store.mode")?.trim()?.lowercase().orEmpty()

        return when {
            explicitMode == "file" -> true
            explicitMode == "jdbc" -> false
            else -> !hasJdbcSettings(environment)
        }
    }
}

private fun hasJdbcSettings(environment: org.springframework.core.env.Environment): Boolean {
    return environment.getProperty("signal-desk.store.jdbc.url").hasText() ||
        (environment.getProperty("signal-desk.store.jdbc.host").hasText() &&
            environment.getProperty("signal-desk.store.jdbc.database").hasText()) ||
        environment.getProperty("JDBC_DATABASE_URL").hasText() ||
        environment.getProperty("DATABASE_URL").hasText() ||
        (environment.getProperty("PGHOST").hasText() && environment.getProperty("PGDATABASE").hasText())
}

private fun String?.hasText(): Boolean = this != null && this.isNotBlank()
