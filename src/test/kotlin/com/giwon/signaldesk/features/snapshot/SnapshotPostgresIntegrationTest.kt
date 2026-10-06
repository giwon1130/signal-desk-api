package com.giwon.signaldesk.features.snapshot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.giwon.signaldesk.features.ai.application.*
import com.giwon.signaldesk.features.market.application.MarketSessionService
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.time.*
import java.util.*

/** Opt-in, loopback-only, random test schema. Never accepts an operational database URL. */
@EnabledIfEnvironmentVariable(named="SIGNAL_DESK_SNAPSHOT_TEST_JDBC_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/snapshot_test")
class SnapshotPostgresIntegrationTest {
    @Test fun `all migrations preserve legacy values and actual snapshot writes are decimal exact and idempotent`() {
        val url = System.getenv("SIGNAL_DESK_SNAPSHOT_TEST_JDBC_URL")
        val password = System.getenv("SIGNAL_DESK_SNAPSHOT_TEST_PASSWORD") ?: "snapshot-test-only"
        val schema = "snapshot_it_" + UUID.randomUUID().toString().replace("-", "")
        val admin = JdbcTemplate(DriverManagerDataSource(url,"postgres",password))
        admin.execute("create schema $schema")
        try {
            val ds = DriverManagerDataSource(url,"postgres",password).apply {
                setConnectionProperties(Properties().apply { setProperty("currentSchema",schema) })
            }
            val jdbc = JdbcTemplate(ds)
            fun migrations(target: String) = Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target).load().migrate()
            migrations("49")
            val legacyUser = UUID.randomUUID()
            jdbc.update("insert into signal_desk_daily_market_snapshot(snapshot_date,nasdaq) values ('2026-10-02',12345.67)")
            jdbc.update("""insert into signal_desk_daily_portfolio_snapshot
                (user_id,snapshot_date,market,evaluation_amount,cost_amount,profit_amount,position_count)
                values (?::uuid,'2026-10-02','US',123.45,100.01,23.44,1)""",legacyUser.toString())
            jdbc.update("""insert into signal_desk_ai_pick_history(id,pick_date,market,ticker,price_at_pick)
                values (?::uuid,'2026-10-02','US','AAPL',123.45)""",UUID.randomUUID().toString())
            migrations("50")
            assertThat(jdbc.queryForObject("select nasdaq from signal_desk_daily_market_snapshot",Double::class.java)).isEqualTo(12345.67)
            assertThat(jdbc.queryForObject("select price_basis from signal_desk_ai_pick_history",String::class.java)).isEqualTo("LEGACY_UNVERIFIED")
            assertThat(jdbc.queryForObject("select price_at_pick from signal_desk_ai_pick_history",Double::class.java)).isEqualTo(123.45)
            assertThat(jdbc.queryForObject("select evaluation_amount from signal_desk_daily_portfolio_snapshot",BigDecimal::class.java)).isEqualByComparingTo("123.45")

            val user = UUID.randomUUID()
            // The KR holding must not be read or combined into the US valuation.
            for ((market,ticker) in listOf("US" to "AAPL","KR" to "005930")) {
                jdbc.update("""insert into signal_desk_portfolio_positions
                    (id,user_id,market,ticker,name,buy_price,current_price,quantity,profit_amount,evaluation_amount,profit_rate)
                    values (?,?::uuid,?,?,?,100.01,999999,0.125,0,0,0)""",UUID.randomUUID().toString(),user.toString(),market,ticker,ticker)
            }
            val now = Instant.parse("2026-10-06T04:40:00Z")
            val policy = SnapshotSessionPolicy(MarketSessionService())
            val session = policy.target("US",now)!!
            val prices = mock(SnapshotClosePrices::class.java)
            val picks = mock(AiPickService::class.java)
            `when`(prices.indices(session)).thenReturn(mapOf("^IXIC" to SnapshotClose(BigDecimal("20000.12"),"YAHOO_FINANCE"),
                "^GSPC" to SnapshotClose(BigDecimal("6000.23"),"YAHOO_FINANCE")))
            `when`(prices.stock("AAPL",session)).thenReturn(SnapshotClose(BigDecimal("101.23"),"YAHOO_FINANCE"))
            val candidate = AiPick("US","AAPL","Apple","검토 후보",null,0,"주문 아님",
                assessment=PickAssessment(PickDecision.REVIEW,listOf("조건 충족"),emptyList(),"rules-test-v1","2026-10-05"))
            `when`(picks.getTodayPicks()).thenReturn(AiPicksResponse(now.toString(),"",listOf(candidate)))
            val service = DailySnapshotService(jdbc,jacksonObjectMapper(),picks,policy,prices,Clock.fixed(now,ZoneOffset.UTC))
            assertThat(service.runDailySnapshot("US")).isEqualTo(DailySnapshotService.Result(true,1,1))
            assertThat(service.runDailySnapshot("US")).isEqualTo(DailySnapshotService.Result(false,0,0))
            val row = jdbc.queryForMap("select * from signal_desk_daily_portfolio_snapshot where user_id=?::uuid",user.toString())
            assertThat(row["snapshot_date"].toString()).isEqualTo("2026-10-05")
            assertThat(row["evaluation_amount"] as BigDecimal).isEqualByComparingTo("12.65375")
            assertThat(row["cost_amount"] as BigDecimal).isEqualByComparingTo("12.50125")
            assertThat(row["profit_amount"] as BigDecimal).isEqualByComparingTo("0.1525")
            assertThat(row["currency"]).isEqualTo("USD")
            assertThat(row["price_basis"]).isEqualTo("CAPTURED_HOLDINGS_AT_SESSION_CLOSE_PRICES")
            val pick = jdbc.queryForMap("select * from signal_desk_ai_pick_history where pick_date='2026-10-05'")
            assertThat(pick["price_at_pick"]).isNull()
            assertThat(pick["reference_close"] as BigDecimal).isEqualByComparingTo("101.23")
            assertThat(pick["assessment"].toString()).contains("REVIEW","rules-test-v1")
            assertThat(jdbc.queryForObject("select count(*) from signal_desk_market_close_snapshot",Int::class.java)).isEqualTo(1)
            verify(prices,never()).stock("005930",session)
        } finally {
            // Only this randomly named schema in the explicitly opt-in loopback test DB is removed.
            admin.execute("drop schema $schema cascade")
        }
    }
}
