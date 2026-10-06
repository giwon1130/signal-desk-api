package com.giwon.signaldesk.features.market.presentation

import com.giwon.signaldesk.features.market.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException

class StockMoveContextControllerTest {
    private val search = mock(StockSearchService::class.java)
    private val reasons = mock(MoverReasonService::class.java)
    private val controller = StockSearchController(search, reasons)

    @Test fun `invalid input never starts news searches`() {
        assertThatThrownBy { controller.context("KR", "x?secret=1") }.isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { controller.context("BAD", "AAPL") }.isInstanceOf(ResponseStatusException::class.java)
        verifyNoInteractions(search, reasons)
    }

    @Test fun `search must resolve exact market and ticker before lookup`() {
        `when`(search.search("005930", "KR", 20)).thenReturn(listOf(StockSearchResult("005935", "삼성전자우", "KR", "", 100, 5.0, "WATCH")))
        assertThat(controller.context("KR", "005930").data).isNull()
        verifyNoInteractions(reasons)
    }
}
