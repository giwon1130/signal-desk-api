package com.giwon.signaldesk.features.reading.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 리딩(Leading Call) 도메인 — 종목 콜 + 시황 공유.
 * spec: signal-desk-app/docs/leading-call-spec.md
 */

enum class LeaderStatus { PENDING, APPROVED, SUSPENDED }
enum class PostVisibility { FOLLOWERS, PUBLIC }
enum class CallStatus { ACTIVE, HIT, CLOSED }
enum class CallCurrency { KRW, USD }

data class Leader(
    val userId: UUID,
    val displayName: String,
    val bio: String,
    val inviteCode: String,
    val status: LeaderStatus,
    val createdAt: Instant,
    /** AI 리더 여부 — 구독 PRO 전용, 둘러보기에서 배지 구분. */
    val isAi: Boolean = false,
)

/** AI 리더 고정 계정 — V32/V34 시드와 동일 UUID. */
object AiLeaders {
    val FLOW: UUID = UUID.fromString("a1f10000-0000-4000-8000-000000000001")    // 🤖 시데 AI 시황
    val YOUTUBE: UUID = UUID.fromString("a1f20000-0000-4000-8000-000000000002") // 📺 삼프로 AI요약
    val REPORT: UUID = UUID.fromString("a1f30000-0000-4000-8000-000000000003")  // 📈 AI 리포트 콜
}

data class Follow(
    val leaderUserId: UUID,
    val followerUserId: UUID,
    val joinedAt: Instant,
)

data class ReadingPost(
    val id: UUID,
    val leaderUserId: UUID,
    val title: String,
    val body: String,
    val visibility: PostVisibility,
    val createdAt: Instant,
)

/**
 * 종목 콜 — entryPrice 는 작성 시점 박제 (immutable, 신뢰 핵심).
 * 현재 수익률은 시세 fetch 해서 derived.
 */
data class ReadingCall(
    val id: UUID,
    val postId: UUID,
    val leaderUserId: UUID,
    val market: String,           // 'KR' | 'US'
    val ticker: String,
    val name: String,
    val entryPrice: BigDecimal,
    val entryCurrency: CallCurrency,
    val entryLockedAt: Instant,
    val targetReturnPct: BigDecimal?,  // null 이면 기본 +15% 알림 기준
    val status: CallStatus,
    val hitAt: Instant?,
    val createdAt: Instant,
)

/** 콜 + 현재 성과 (derived — 시세 fetch 후 계산). */
data class CallPerformance(
    val call: ReadingCall,
    val currentPrice: BigDecimal?,     // 시세 없으면 null
    val returnPct: Double?,            // (현재가 - entry) / entry, 시세 없으면 null
)
