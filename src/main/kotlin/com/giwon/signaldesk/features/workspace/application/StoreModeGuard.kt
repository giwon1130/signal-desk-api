package com.giwon.signaldesk.features.workspace.application

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component

/**
 * store-mode 정합 가드 — "split-brain" 방지.
 *
 * DataSource/Flyway·Auth 등 일부 빈은 [JdbcStoreCondition] 으로 DB 설정을 '자동감지'해 켜지지만,
 * League·Reading·Plan·Admin·Push-pref 등 대다수 빈은 `@ConditionalOnProperty(...havingValue="jdbc")` 라
 * **`signal-desk.store.mode` 가 명시적으로 "jdbc" 일 때만** 켜진다.
 *
 * 따라서 DB 는 붙어 있는데(mode 미설정) 자동감지로 JDBC 가 켜지면, auth/workspace 만 살고
 * league/plan/admin 은 조용히 사라지며 FREE 쿼터 강제까지 누락된다. 이 빈은 JDBC 가 활성인데
 * mode 가 "jdbc" 가 아니면 기동을 즉시 실패시켜 그 위험한 반쪽 상태를 막는다.
 */
@Component
@Conditional(JdbcStoreCondition::class)
class StoreModeGuard(
    @Value("\${signal-desk.store.mode:}") private val mode: String,
) {
    @PostConstruct
    fun verify() {
        if (!mode.trim().equals("jdbc", ignoreCase = true)) {
            throw IllegalStateException(
                "[CONFIG] DB 설정이 감지돼 JDBC 모드로 동작하지만 signal-desk.store.mode 가 'jdbc' 가 아닙니다(현재='${mode}'). " +
                    "이 상태면 league/plan/admin 등 다수 기능이 조용히 비활성화되고 FREE 쿼터 강제가 누락됩니다. " +
                    "운영에서는 SIGNAL_DESK_STORE_MODE=jdbc 를 반드시 설정하세요.",
            )
        }
    }
}
