package com.giwon.signaldesk.bootstrap

import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
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
}
