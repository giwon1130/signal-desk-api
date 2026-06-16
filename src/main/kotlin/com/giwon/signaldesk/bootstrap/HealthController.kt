package com.giwon.signaldesk.bootstrap

import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

data class HealthResponse(
    val status: String,
    val application: String,
    val storeMode: String,
    val db: String,
)

@RestController
class HealthController(
    private val dataSource: ObjectProvider<DataSource>,
) {
    @GetMapping("/health")
    fun health(): HealthResponse {
        // 실제 wiring 결과를 보고 — DATABASE_URL 설정이 깨져 file 모드로 떨어지면
        // (auth/league/push 등 jdbc 전용 기능이 전부 비활성) 여기서 바로 드러난다.
        val ds = dataSource.ifAvailable
        val mode = if (ds != null) "jdbc" else "file"
        // jdbc 모드면 실제 커넥션 roundtrip(select 1)으로 DB 가용성 확인 — wiring 만으론 못 잡는 단절 감지.
        // HTTP 200 은 유지(일시 블립에 Railway 재시작 루프 방지), db 필드로 상태 노출.
        val db = when {
            ds == null -> "n/a"
            else -> runCatching {
                ds.connection.use { c -> c.createStatement().use { it.executeQuery("select 1").close() } }
                "up"
            }.getOrDefault("down")
        }
        return HealthResponse(
            status = "UP",
            application = "signal-desk-api",
            storeMode = mode,
            db = db,
        )
    }
}
