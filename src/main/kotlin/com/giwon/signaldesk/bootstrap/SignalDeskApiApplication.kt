package com.giwon.signaldesk.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.giwon.signaldesk"])
class SignalDeskApiApplication

fun main(args: Array<String>) {
    runApplication<SignalDeskApiApplication>(*args)
}
