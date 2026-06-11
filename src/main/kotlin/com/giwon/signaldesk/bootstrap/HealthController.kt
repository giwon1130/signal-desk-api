package com.giwon.signaldesk.bootstrap

import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

data class HealthResponse(
    val status: String,
    val application: String,
    val storeMode: String,
)

@RestController
class HealthController(
    private val dataSource: ObjectProvider<DataSource>,
) {
    @GetMapping("/health")
    fun health(): HealthResponse {
        // 실제 wiring 결과를 보고 — DATABASE_URL 설정이 깨져 file 모드로 떨어지면
        // (auth/league/push 등 jdbc 전용 기능이 전부 비활성) 여기서 바로 드러난다.
        val mode = if (dataSource.ifAvailable != null) "jdbc" else "file"
        return HealthResponse(
            status = "UP",
            application = "signal-desk-api",
            storeMode = mode,
        )
    }
}
