package com.giwon.signaldesk.features.rounds.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * 급락·실적 등 특정 시장 이벤트에만 여는 읽기 중심 라운드.
 * 일반 게시판처럼 상시 노출하지 않고, 운영자가 검수한 원문 링크와 확인 항목만 제공한다.
 */
data class MarketRound(
    val id: String,
    val title: String,
    val summary: String,
    val riskLevel: String,
    val marketScope: String,
    val checkpoints: List<String>,
    val startsAt: Instant,
    val endsAt: Instant,
    val contents: List<MarketRoundContent>,
)

data class MarketRoundContent(
    val id: String,
    val kind: String,
    val sourceName: String,
    val expertName: String?,
    val title: String,
    val url: String,
    val publishedAt: Instant?,
    val whyRecommended: String,
    val label: String,
    val official: Boolean,
)

data class MarketRoundDraft(
    val id: String = "",
    val title: String,
    val summary: String,
    val riskLevel: String = "CAUTION",
    val marketScope: String = "BOTH",
    val checkpoints: List<String> = emptyList(),
    val startsAt: Instant,
    val endsAt: Instant,
    val contents: List<MarketRoundContentDraft> = emptyList(),
)

data class MarketRoundContentDraft(
    val kind: String = "VIDEO",
    val sourceName: String,
    val expertName: String? = null,
    val title: String,
    val url: String,
    val publishedAt: Instant? = null,
    val whyRecommended: String,
    val label: String = "시장 해설",
    val official: Boolean = true,
)

@Service
@ConditionalOnProperty(prefix = "signal-desk.store", name = ["mode"], havingValue = "jdbc")
class MarketRoundService(private val jdbc: JdbcTemplate) {

    fun active(): MarketRound? = jdbc.query(
        """
        select * from signal_desk_market_rounds
        where starts_at <= now() and ends_at > now()
        order by case risk_level when 'HIGH' then 3 when 'CAUTION' then 2 else 1 end desc, starts_at desc
        limit 1
        """.trimIndent(),
        { rs, _ -> roundFrom(rs) },
    ).firstOrNull()?.let(::withContents)

    fun findById(id: String): MarketRound? = jdbc.query(
        "select * from signal_desk_market_rounds where id = ?",
        { rs, _ -> roundFrom(rs) }, id,
    ).firstOrNull()?.let(::withContents)

    @Transactional
    fun save(draft: MarketRoundDraft): MarketRound {
        require(draft.title.isNotBlank()) { "라운드 제목을 입력해 주세요." }
        require(draft.summary.isNotBlank()) { "라운드 설명을 입력해 주세요." }
        require(draft.endsAt.isAfter(draft.startsAt)) { "종료 시점은 시작 시점 이후여야 합니다." }
        require(draft.marketScope in setOf("KR", "US", "BOTH", "GLOBAL")) { "marketScope 값이 올바르지 않습니다." }
        require(draft.riskLevel in setOf("WATCH", "CAUTION", "HIGH")) { "riskLevel 값이 올바르지 않습니다." }
        require(draft.contents.size <= MAX_CONTENTS) { "콘텐츠는 최대 ${MAX_CONTENTS}개까지 등록할 수 있어요." }

        val id = draft.id.trim().ifBlank { UUID.randomUUID().toString() }
        jdbc.update(
            """
            insert into signal_desk_market_rounds
              (id, title, summary, risk_level, market_scope, checkpoints, starts_at, ends_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
              title = excluded.title, summary = excluded.summary, risk_level = excluded.risk_level,
              market_scope = excluded.market_scope, checkpoints = excluded.checkpoints,
              starts_at = excluded.starts_at, ends_at = excluded.ends_at, updated_at = now()
            """.trimIndent(),
            id, draft.title.trim().take(100), draft.summary.trim().take(700), draft.riskLevel, draft.marketScope,
            draft.checkpoints.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_CHECKPOINTS).joinToString("\n"),
            Timestamp.from(draft.startsAt), Timestamp.from(draft.endsAt),
        )
        jdbc.update("delete from signal_desk_market_round_contents where round_id = ?", id)
        draft.contents.forEachIndexed { index, content ->
            require(content.sourceName.isNotBlank() && content.title.isNotBlank() && content.whyRecommended.isNotBlank()) {
                "콘텐츠의 출처·제목·추천 사유를 모두 입력해 주세요."
            }
            require(content.url.startsWith("https://")) { "콘텐츠 링크는 https 주소여야 합니다." }
            jdbc.update(
                """
                insert into signal_desk_market_round_contents
                  (id, round_id, kind, source_name, expert_name, title, url, published_at, why_recommended, label, official, display_order)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(), id, content.kind.trim().uppercase().take(24), content.sourceName.trim().take(80),
                content.expertName?.trim()?.takeIf { it.isNotBlank() }?.take(80), content.title.trim().take(300), content.url.trim(),
                content.publishedAt?.let(Timestamp::from), content.whyRecommended.trim().take(300), content.label.trim().ifBlank { "시장 해설" }.take(40),
                content.official, index,
            )
        }
        return requireNotNull(findById(id))
    }

    private fun withContents(round: MarketRound): MarketRound = round.copy(contents = jdbc.query(
        "select * from signal_desk_market_round_contents where round_id = ? order by display_order asc, created_at desc",
        { rs, _ ->
            MarketRoundContent(
                id = rs.getString("id"), kind = rs.getString("kind"), sourceName = rs.getString("source_name"),
                expertName = rs.getString("expert_name"), title = rs.getString("title"), url = rs.getString("url"),
                publishedAt = rs.getTimestamp("published_at")?.toInstant(), whyRecommended = rs.getString("why_recommended"),
                label = rs.getString("label"), official = rs.getBoolean("official"),
            )
        }, round.id,
    ))

    private fun roundFrom(rs: java.sql.ResultSet) = MarketRound(
        id = rs.getString("id"), title = rs.getString("title"), summary = rs.getString("summary"),
        riskLevel = rs.getString("risk_level"), marketScope = rs.getString("market_scope"),
        checkpoints = rs.getString("checkpoints").orEmpty().lineSequence().map(String::trim).filter(String::isNotBlank).toList(),
        startsAt = rs.getTimestamp("starts_at").toInstant(), endsAt = rs.getTimestamp("ends_at").toInstant(), contents = emptyList(),
    )

    private companion object {
        const val MAX_CONTENTS = 5
        const val MAX_CHECKPOINTS = 4
    }
}
