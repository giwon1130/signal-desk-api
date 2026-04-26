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

(stub) `Controller` 별로 묶음.

### `bootstrap/HealthController`
- `GET /health` → `{ "status": "ok" }`

### `features/auth/AuthController` (jdbc 모드만)
- `POST /auth/signup` body `{ email, password, nickname }` → `AuthResult`
- `POST /auth/login` body `{ email, password }` → `AuthResult`
- `POST /auth/oauth/google` body `{ idToken }` → `AuthResult`
- `POST /auth/oauth/kakao` body `{ accessToken }` → `AuthResult`
- `GET /auth/me` (Bearer) → `AuthResult`

### `features/market/MarketOverviewController`
- `GET /api/v1/market/overview` (Bearer optional) → 풀
- `GET /api/v1/market/summary` (Bearer optional)
- `GET /api/v1/market/sections`
- `GET /api/v1/market/news`
- `GET /api/v1/market/ai-recommendations` (Bearer optional)

### `features/market/StockSearchController`
- `GET /api/v1/market/stocks/search?q=...&market=ALL|KR|US`
- `GET /api/v1/market/movers?limit=10`

### `features/workspace/WorkspaceController` (Bearer)
- `GET / POST / DELETE /api/v1/workspace/watchlist[/{id}]`
- `GET / POST / DELETE /api/v1/workspace/portfolio[/{id}]`
- `GET / POST / DELETE /api/v1/workspace/paper/positions[/{id}]`
- `GET / POST / DELETE /api/v1/workspace/paper/trades[/{id}]`
- `GET / POST / DELETE /api/v1/workspace/ai/picks[/{id}]`
- `GET / POST / DELETE /api/v1/workspace/ai/track-records[/{id}]`

### `features/push/PushDeviceController` (Bearer)
- `POST /api/v1/push/devices` body `{ expoToken, platform }`
- `DELETE /api/v1/push/devices/{token}`

### `features/push/PushAlertController` (Bearer)
- `GET /api/v1/push/alerts/preferences`
- `POST /api/v1/push/alerts/preferences` body `{ enabled }`
- `GET /api/v1/push/alerts/history?limit=10`

### `features/fortune/FortuneController`
- `GET /api/v1/workspace/fortune` (Bearer optional)

### `features/realtime/PriceTickerHandler` (WebSocket)
- `wss://.../ws/prices`
- 메시지 포맷: [엔지니어링/아키텍처.md](아키텍처.md) 참고

### `features/kakao/KakaoWebhookController`
- 외부 — KakaoTalk 챗봇 webhook

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
