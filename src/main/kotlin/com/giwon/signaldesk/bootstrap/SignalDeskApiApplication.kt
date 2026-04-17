package com.giwon.signaldesk.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication(
    scanBasePackages = ["com.giwon.signaldesk"],
    exclude = [DataSourceAutoConfiguration::class],
)
class SignalDeskApiApplication

fun main(args: Array<String>) {
    runApplication<SignalDeskApiApplication>(*args)
}
