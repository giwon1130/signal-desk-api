package com.giwon.signaldesk.features.auth.application

import com.giwon.signaldesk.features.workspace.application.JdbcStoreCondition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Conditional
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 회원 탈퇴 — 사용자와 모든 user-scoped 데이터를 트랜잭션으로 일괄 삭제.
 *
 * FK 제약(대부분 ON DELETE CASCADE 아님) 때문에 자식 → 부모 순으로 삭제 후 마지막에 users.
 * paper_* 테이블은 V13 에서 drop 됨 → 대상 아님. disclosure_seen/media_summaries 는 user-scoped 아님.
 * alert_preferences 는 users 에 CASCADE 지만 명시적으로도 지운다(가독성).
 */
@Service
@Conditional(JdbcStoreCondition::class)
class AccountDeletionService(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun deleteAccount(userId: UUID) {
        val uid = userId.toString()
        fun del(sql: String) = jdbc.update(sql, uid)

        // 1) 리딩(소셜) — 팔로우 양방향(리더/팔로워) → 콜 → 글 → 리더
        del("delete from signal_desk_reading_follow where leader_user_id = ?::uuid")
        del("delete from signal_desk_reading_follow where follower_user_id = ?::uuid")
        del("delete from signal_desk_reading_call where leader_user_id = ?::uuid")
        del("delete from signal_desk_reading_post where leader_user_id = ?::uuid")  // 남은 콜은 post_id CASCADE
        del("delete from signal_desk_reading_leader where user_id = ?::uuid")

        // 2) 모의투자 리그 — 반응 → 트레이드 → 참가 → (내가 호스트인) 리그
        del("delete from signal_desk_mock_reaction where user_id = ?::uuid")
        del("delete from signal_desk_mock_trade where user_id = ?::uuid")          // 그 트레이드의 반응은 CASCADE
        del("delete from signal_desk_mock_participant where user_id = ?::uuid")
        del("delete from signal_desk_mock_league where host_user_id = ?::uuid")    // 참가/트레이드/반응 CASCADE

        // 3) 워크스페이스/콘텐츠
        del("delete from signal_desk_watchlist where user_id = ?::uuid")
        del("delete from signal_desk_portfolio_positions where user_id = ?::uuid")
        del("delete from signal_desk_ai_picks where user_id = ?::uuid")
        del("delete from signal_desk_ai_track_records where user_id = ?::uuid")
        del("delete from signal_desk_seasonality_rules where user_id = ?::uuid")

        // 4) 푸시/알림
        del("delete from signal_desk_push_devices where user_id = ?::uuid")
        del("delete from signal_desk_push_alert_log where user_id = ?::uuid")
        del("delete from signal_desk_alert_preferences where user_id = ?::uuid")

        // 5) 마지막으로 사용자 본체
        val removed = del("delete from signal_desk_users where id = ?::uuid")
        log.info("account deleted — userId={}, usersRowDeleted={}", userId, removed)
    }
}
