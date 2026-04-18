package com.giwon.signaldesk.features.auth.application

import org.springframework.context.annotation.Conditional
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import com.giwon.signaldesk.features.workspace.application.JdbcStoreCondition
import java.util.UUID

@Repository
@Conditional(JdbcStoreCondition::class)
class UserRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper { rs, _ ->
        SignalUser(
            id           = UUID.fromString(rs.getString("id")),
            email        = rs.getString("email"),
            passwordHash = rs.getString("password"),
            nickname     = rs.getString("nickname"),
            googleId     = rs.getString("google_id"),
            kakaoId      = rs.getString("kakao_id"),
            createdAt    = rs.getTimestamp("created_at").toInstant(),
        )
    }

    fun findByEmail(email: String): SignalUser? =
        jdbc.query("SELECT * FROM signal_desk_users WHERE email = ?", rowMapper, email).firstOrNull()

    fun findById(id: UUID): SignalUser? =
        jdbc.query("SELECT * FROM signal_desk_users WHERE id = ?", rowMapper, id).firstOrNull()

    fun findByGoogleId(googleId: String): SignalUser? =
        jdbc.query("SELECT * FROM signal_desk_users WHERE google_id = ?", rowMapper, googleId).firstOrNull()

    fun findByKakaoId(kakaoId: String): SignalUser? =
        jdbc.query("SELECT * FROM signal_desk_users WHERE kakao_id = ?", rowMapper, kakaoId).firstOrNull()

    fun existsByEmail(email: String): Boolean =
        (jdbc.queryForObject("SELECT COUNT(*) FROM signal_desk_users WHERE email = ?", Int::class.java, email) ?: 0) > 0

    fun save(email: String, passwordHash: String, nickname: String): SignalUser {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO signal_desk_users (id, email, password, nickname) VALUES (?, ?, ?, ?)",
            id, email, passwordHash, nickname,
        )
        return findById(id)!!
    }

    fun saveOAuthUser(email: String, nickname: String, googleId: String? = null, kakaoId: String? = null): SignalUser {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO signal_desk_users (id, email, nickname, google_id, kakao_id) VALUES (?, ?, ?, ?, ?)",
            id, email, nickname, googleId, kakaoId,
        )
        return findById(id)!!
    }

    fun linkGoogleId(userId: UUID, googleId: String) {
        jdbc.update("UPDATE signal_desk_users SET google_id = ? WHERE id = ?", googleId, userId)
    }

    fun linkKakaoId(userId: UUID, kakaoId: String) {
        jdbc.update("UPDATE signal_desk_users SET kakao_id = ? WHERE id = ?", kakaoId, userId)
    }
}
