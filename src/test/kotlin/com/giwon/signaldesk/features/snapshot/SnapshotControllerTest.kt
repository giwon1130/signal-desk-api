package com.giwon.signaldesk.features.snapshot

import com.giwon.signaldesk.bootstrap.ForbiddenException
import com.giwon.signaldesk.bootstrap.ValidationExceptionHandler
import com.giwon.signaldesk.features.admin.AdminGuard
import com.giwon.signaldesk.features.auth.application.AuthContext
import com.giwon.signaldesk.features.auth.application.AuthException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class SnapshotControllerTest {
    @Test fun `snapshot writes require admin and exact market and preserve response compatibility`() {
        val service = mock(DailySnapshotService::class.java)
        val auth = mock(AuthContext::class.java)
        val guard = mock(AdminGuard::class.java)
        val user = UUID.randomUUID()
        val admin = UUID.randomUUID()
        `when`(auth.requireUserId(null)).thenThrow(AuthException("login"))
        `when`(auth.requireUserId("Bearer user")).thenReturn(user)
        `when`(auth.requireUserId("Bearer admin")).thenReturn(admin)
        doThrow(ForbiddenException()).`when`(guard).requireAdmin(user)
        val mvc = MockMvcBuilders.standaloneSetup(SnapshotController(auth,guard,service))
            .setControllerAdvice(ValidationExceptionHandler()).build()
        mvc.post("/api/v1/snapshots/run").andExpect { status { isUnauthorized() } }
        mvc.post("/api/v1/snapshots/run") { header("Authorization","Bearer user") }.andExpect { status { isForbidden() } }
        mvc.post("/api/v1/snapshots/run?market=EU") { header("Authorization","Bearer admin") }.andExpect { status { isBadRequest() } }
        verifyNoInteractions(service)
        `when`(service.runDailySnapshot("US")).thenReturn(DailySnapshotService.Result(true,2,3))
        mvc.post("/api/v1/snapshots/run?market=US") { header("Authorization","Bearer admin") }.andExpect {
            status { isOk() }
            jsonPath("$.data.marketSaved") { value(true) }
            jsonPath("$.data.aiPicksSaved") { value(2) }
            jsonPath("$.data.portfolioRows") { value(3) }
        }
        verify(service).runDailySnapshot("US")
        `when`(service.runDailySnapshot("KR")).thenReturn(DailySnapshotService.Result(false,0,0))
        mvc.post("/api/v1/snapshots/run") { header("Authorization","Bearer admin") }.andExpect { status { isOk() } }
        verify(service).runDailySnapshot("KR")
    }
}
