package com.giwon.signaldesk.bootstrap

import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// store.mode=file 고정 — CI 환경에 DATABASE_URL 이 있어도 StoreModeGuard 가 컨텍스트를 막지 않게(테스트 격리).
@SpringBootTest(properties = ["signal-desk.store.mode=file"])
@AutoConfigureMockMvc
class HealthControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `health endpoint returns up`() {
        mockMvc.get("/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status", equalTo("UP"))
                jsonPath("$.application", equalTo("signal-desk-api"))
            }
    }

    @Test
    fun `존재하지 않는 경로는 500이 아니라 404`() {
        mockMvc.get("/nonexistent-path")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error", equalTo("요청한 경로를 찾을 수 없습니다."))
            }
    }
}
