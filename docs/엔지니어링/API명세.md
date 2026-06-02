# API 명세 (백엔드 SoT)

> **누가 봄**: BE / FE / 외부 통합
> **언제 봄**: 새 endpoint 만들 때 / 디버깅
> **이게 진실**: app 저장소의 API명세 는 FE 관점 요약, 정식 정의는 여기.

## Base URL

- 운영: `https://signal-desk-api-production.up.railway.app`
- 로컬: `http://localhost:8091`

## 인증

(stub)
- 모든 인증 필요 endpoint 는 `Authorization: Bearer <jwt>` 헤더
- file mode 에선 `/auth/*` 비활성 (404)
- `@Conditional(JdbcStoreCondition::class)` 로 인증 모듈 게이팅

## Rate limit

| 경로 | 한도 | 응답 |
|------|------|------|
| `/auth/**` | 5 / 분 / IP | 429 + `Retry-After: 60` |
| `/api/v1/market/**` | 60 / 분 / IP | 동일 |

## 엔드포인트 목록

`Controller` 별로 묶음. jdbc 모드 전용 controller 는 `@ConditionalOnProperty(signal-desk.store.mode=jdbc)` 로 게이팅 — file 모드에선 빈 등록 안 됨(404).

### `bootstrap/HealthController`
- `GET /health` → `{ "status": "ok" }`

### `features/auth/AuthController` (jdbc 모드만)
- `POST /auth/signup` body `{ email, password, nickname }` → `AuthResult`
- `POST /auth/login` body `{ email, password }` → `AuthResult`
- `POST /auth/oauth/google` body `{ idToken }` → `AuthResult`
- `POST /auth/oauth/kakao` body `{ accessToken }` → `AuthResult`
- `GET /auth/me` (Bearer) → `AuthResult`

### `features/market/MarketOverviewController`
- `GET /api/v1/market/summary` (Bearer optional) — `compositeRisk`, `marketSessions`, `watchAlerts` 포함
- `GET /api/v1/market/sections`
- `GET /api/v1/market/watchlist` (Bearer optional)
- `GET /api/v1/market/portfolio` (Bearer optional)
- `GET /api/v1/market/ai-recommendations` (Bearer optional)
- `GET /api/v1/market/top-movers?limit=10` — 급등/급락 상위 (KOSPI/KOSDAQ)

### `features/market/StockSearchController`
- `GET /api/v1/market/stocks/search?q=...&market=ALL|KR|US&limit=20`

### `features/ai/AiPickController`
- `GET /api/v1/ai/picks` → 오늘의 AI 픽 (Gemini 미설정/후보 없음이면 `data=null`)
- `GET /api/v1/ai/signals` (Bearer optional) → 숨은 시그널 (보유/관심 종목 공시·수급·급등락)

### `features/workspace/WorkspaceController` (Bearer optional — 없으면 글로벌 데이터)
- `GET / POST /api/v1/workspace/watchlist`, `DELETE /api/v1/workspace/watchlist/{id}`
- `GET / POST /api/v1/workspace/portfolio`, `DELETE /api/v1/workspace/portfolio/{id}`
- `GET /api/v1/workspace/ai/picks`
- `GET /api/v1/workspace/ai/track-records`

### `features/fortune/FortuneController`
- `GET /api/v1/workspace/fortune` (Bearer optional)

### `features/media/MediaSummaryController` (jdbc 모드만)
- `GET /api/v1/media/summaries/latest` → 최신 미디어 요약 (없으면 null)
- `GET /api/v1/media/morning-brief` → 오늘의 모닝 브리프 (없으면 null)
- `POST /api/v1/media/morning-brief/refresh?force=false` — 운영용 수동 트리거
- `POST /api/v1/media/intraday-brief/refresh?slot=MIDDAY|CLOSE&force=false` — 장중/마감 브리프 수동 트리거
- `POST /api/v1/media/summaries/news-digest?market=KR|US|ALL&force=false` — 뉴스 종합 수동 트리거

### `features/media/MarketInsightController`
- `GET /api/v1/insights/today` → 오늘의 시황 인사이트 (없으면 null)
- `POST /api/v1/insights/today/refresh` — 캐시 무효화 + Gemini 재호출 (운영용)

### `features/events/MarketEventController`
- `GET /api/v1/events/upcoming?days=14` → 다가오는 시장 이벤트
- `GET /api/v1/events/today` → 오늘 이벤트

### `features/disclosure/DartDisclosureController` (jdbc 모드만)
- `GET /api/v1/disclosures/recent?limit=30` (Bearer optional) → 보유/관심 KR 종목 최근 공시 (비로그인이면 빈 리스트)
- `POST /api/v1/disclosures/scan` — 운영용 수동 트리거 (인증 없음, 외부 차단 전제)

### `features/league/LeagueController` (jdbc 모드만 / Bearer)
- `POST /api/v1/league` body `{ name, marketScope, currency, startingCapital, startedAt, endsAt, ... }` → DRAFT league 생성 (호스트 자동 첫 참가자)
- `GET /api/v1/league/my` → 내 참여 league 목록
- `GET /api/v1/league/{id}` → league 상세 + 참가자 (리더보드용)
- `POST /api/v1/league/{id}/open` → DRAFT → OPEN (호스트만)
- `POST /api/v1/league/join` body `{ joinCode, nickname, avatarEmoji }` → 코드로 참가
- `DELETE /api/v1/league/{id}/leave` → 참가 취소 (DRAFT/OPEN 만)

### `features/league/TradeController` (jdbc 모드만 / Bearer)
- `POST /api/v1/league/{leagueId}/trades` body `{ market, ticker, side, quantity, name }` → 매수/매도 (백엔드가 시세 fetch + 검증 + INSERT)
- `GET /api/v1/league/{leagueId}/trades?limit=100` → 거래 피드 (visibility=OPEN 이면 전체, CLOSED 면 본인만)
- `GET /api/v1/league/{leagueId}/trades/me` → 내 거래
- `GET /api/v1/league/{leagueId}/trades/positions` → 내 현재 보유 (trade 합산 derived — DB 저장 X)

### `features/league/LeaderboardController` (jdbc 모드만 / Bearer)
- `GET /api/v1/league/{leagueId}/leaderboard` → 순위 (총자산/수익률/랭크)

### `features/reading/ReadingController` (jdbc 모드만 / Bearer)
- `POST /api/v1/reading/leader/apply` body `{ displayName, bio }` → 리더 신청 (운영자는 즉시 승인)
- `GET /api/v1/reading/leader/me` → 내 리더 프로필 (없으면 null)
- `POST /api/v1/reading/leader/{targetUserId}/approve` → 운영자가 PENDING 리더 승인
- `POST /api/v1/reading/subscribe` body `{ inviteCode }` → 리더 구독
- `DELETE /api/v1/reading/subscribe/{leaderUserId}` → 구독 취소
- `GET /api/v1/reading/following` → 내가 구독 중인 리더 목록
- `POST /api/v1/reading/detect` body `{ body }` → 본문에서 종목 후보 검출
- `POST /api/v1/reading/posts` body `{ title, body, visibility, calls[] }` → 리딩 글 게시 + 콜 가격 박제 (APPROVED 리더만)

### `features/reading/ReadingFeedController` (jdbc 모드만 / Bearer)
- `GET /api/v1/reading/feed?limit=50` → 내 피드 (구독 리더 + 본인 글)
- `GET /api/v1/reading/leader/{leaderUserId}/profile` → 리더 프로필 (통계 + 글 목록)

### `features/push/PushDeviceController` (Bearer)
- `POST /api/v1/push/devices` body `{ platform, expoToken }`
- `DELETE /api/v1/push/devices/{token}`

### `features/push/PushAlertHistoryController` (Bearer)
- `GET /api/v1/push/alerts?limit=30` → 알림 발송 이력

### `features/push/PushStatsController`
- `GET /api/v1/push/stats?days=7` → 최근 N일 알림 발송 통계 (인증 없음, 운영용)

### `features/push/AlertPreferenceController` (jdbc 모드만 / Bearer)
- `GET /api/v1/me/alert-preferences` → 알림 설정
- `PUT /api/v1/me/alert-preferences` body `AlertPreferences` → 갱신

### `features/system/SystemStatusController`
- `GET /api/v1/system/status` → 외부 의존성(Gemini) 헬스 (인증 없음)

### `features/realtime/PriceTickerHandler` (WebSocket)
- `wss://.../ws/prices`
- 메시지 포맷: [엔지니어링/아키텍처.md](아키텍처.md) 참고

## 응답 wrapper

```kotlin
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
)
```

## 에러 응답

(stub) `RestControllerAdvice` 가 처리.
| 예외 | HTTP | error 메시지 |
|------|------|-------------|
| `AuthException` | 400 | 한국어 메시지 |
| `MethodArgumentNotValidException` | 400 | "validation 실패" |
| 그 외 | 500 | "서버 오류가 발생했어요" |
| Rate limit | 429 | "요청이 너무 많아요. 잠시 후 다시 시도해주세요." |

## 호환성 정책

(stub)
- 새 필드: 모두 nullable + 기본값
- 필드 제거: deprecated 마킹 → 1 사이클 후
- 새 enum value: 클라이언트가 unknown fallback
