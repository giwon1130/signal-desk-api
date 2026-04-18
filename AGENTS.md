# Signal Desk API — Agent Notes

Spring Boot Kotlin 백엔드. 모바일 앱(`../signal-desk-app`)에 데이터 공급. Railway 배포.

## 도메인 한 줄

한국/미국 주식 시장 요약 + 사용자 워크스페이스(관심종목·실제 보유·페이퍼 트레이딩·AI 추천 로그) +
대안 데이터(Pentagon Pizza Index 등) + 뉴스 sentiment + 차트 시뮬레이션을 한 서버에서 묶어서 내려준다.

## 주요 엔드포인트

```
GET  /api/v1/market/overview      # 풀 응답 (모든 도메인)
GET  /api/v1/market/summary       # 요약 (지표·세션·뉴스 sentiment·tradingDayStatus)
GET  /api/v1/market/sections      # 한·미 시장 + 차트
GET  /api/v1/market/news          # 뉴스 피드

GET  /api/v1/workspace/watchlist           # 관심종목
POST /api/v1/workspace/watchlist           # 추가/수정
DELETE /api/v1/workspace/watchlist/{id}    # 해제

GET  /api/v1/workspace/portfolio           # 실제 보유 (HoldingPosition)
POST /api/v1/workspace/portfolio           # 등록/수정 (buyPrice + quantity 필수)
DELETE /api/v1/workspace/portfolio/{id}

# AI / paper / 인증 (자세히는 *Controller.kt)
```

## 코어 패키지 (`features/market/application/`)

| 클래스 | 책임 |
|--------|------|
| `MarketOverviewService` | 진입점. 캐시 + 동시 외부호출 + 응답 조립. |
| `MarketOverviewModel.kt` | 모든 응답·도메인 타입 (앱 `src/types.ts`와 1:1) |
| `IndexChartFactory` | D/W/M 캔들 시뮬레이션 (각 캔들 = 1 단위) |
| `NewsSentimentBuilder` | 뉴스 RSS → 키워드 기반 긍정/중립/부정 분류 |
| `AlternativeSignalService` | Pizza Index + Policy Buzz + Bar Counter-Signal |
| `MarketSessionService` | 한국/미국 장 세션 + 휴장·조기종료 처리 |
| `WatchAlertService` | "관심종목 시그널" 산출 |
| `WorkspaceEnrichmentService` | 사용자 데이터(관심·보유·AI 로그) + 라이브 시세 결합 |

외부 클라이언트:
- `KrxOfficialClient` — 한국 지수
- `NaverFinanceQuoteClient`, `NaverStockSearchClient` — 한국 종목
- `CboeVixClient`, `FredIndexClient` — 미국 지수/VIX
- `GoogleNewsRssClient` — 뉴스
- `PizzIntClient`, `VenueSignalCollector` — 실험 지표

## 캐싱

- `MarketOverviewService` 안에 `cachedCore` (60초 TTL) + `cachedNews` (5분 TTL)
- 외부 API 장애 시 `runCatching { ... }.getOrElse { default }`로 fallback (전체 엔드포인트가 죽지 않도록)

## 차트 시맨틱 — 한 캔들 = 한 단위

`IndexChartFactory`:
- `D` 일봉 30개
- `W` 주봉 20개
- `M` 월봉 12개

실측 데이터(`baseSeries`)가 충분(절반 이상) + 변동폭 0.2% 이상일 때만 사용, 아니면 시드된 random walk로 시뮬레이션.
시드 = `today.toEpochDay() * 1000L + (latest / 100).toLong()` → 같은 날엔 같은 모양.

## 실험 지표

각 항목에 `description`(이게 뭐야?) + `methodology`(점수 계산식) 필드를 채워서 내려보냄.
앱 모달에서 그대로 표시되므로 한국어로 일반인이 읽을 수 있게.

## 휴장/주말 처리

`MarketOverviewService.buildTradingDayStatus`:
- 두 시장 모두 닫혔고 둘 다 note에 "주말" → `isWeekend=true`
- 둘 다 닫혔고 note에 "휴장" → `isHoliday=true`
- `headline`(짧은 문장) + `advice`(행동 가이드) + `nextTradingDay`(예: "월요일 2026-04-20 09:00 KST") 동봉
- 앱 Today 탭이 이 객체로 배너·카피 자동 전환

## 개발 환경 셋업

요구사항: JDK 21, Gradle Wrapper(저장소에 동봉), Docker(선택, JDBC 모드 로컬 검증용).

```bash
# 의존성 받고 빠른 컴파일 체크
./gradlew compileKotlin

# 단위 테스트
./gradlew test

# 로컬 실행 (기본 포트 8091, 파일 저장 모드)
./gradlew bootRun

# JDBC(PostgreSQL) 모드로 로컬에서 돌리고 싶을 때
docker compose -f docker-compose.jdbc.yml up -d
SIGNAL_DESK_STORE_MODE=jdbc \
  JDBC_DATABASE_URL=jdbc:postgresql://localhost:5432/signaldesk \
  PGUSER=signaldesk PGPASSWORD=signaldesk \
  ./gradlew bootRun
```

저장 모드:
- `signal-desk.store.mode=` (빈 값) → 자동 감지. `JDBC_DATABASE_URL`/`DATABASE_URL`/`PG*`이 있으면 JDBC, 없으면 파일.
- `signal-desk.store.mode=file` → `./data/signal-desk-store.json`에 JSON 직접 저장.
- `signal-desk.store.mode=jdbc` → PostgreSQL. 부팅 시 `V*__*.sql` 자동 마이그레이션.

## 환경변수 (운영)

| 키 | 용도 | 비고 |
|----|------|------|
| `PORT` | 서버 포트 | Railway가 주입. 미설정 시 8091. |
| `SIGNAL_DESK_STORE_MODE` | 저장 모드 | `jdbc` 권장 (운영) |
| `JDBC_DATABASE_URL` / `DATABASE_URL` | PG 연결 | Railway PostgreSQL 어태치 시 자동 |
| `PGUSER`, `PGPASSWORD`, `PGHOST`, `PGPORT`, `PGDATABASE` | PG 보조 | `DATABASE_URL` 없을 때 폴백 |
| `signal-desk.jwt.secret` | JWT 서명 키 | **운영에서는 반드시 32바이트 이상으로 교체.** 미설정 시 코드 내 더미 값 사용 |
| `signal-desk.jwt.expiration-hours` | 토큰 유효시간 | 기본 720h (30일) |
| `SIGNAL_DESK_CORS_ALLOWED_ORIGINS` | CORS 허용 패턴 | 콤마 구분, 비우면 전부 차단 |

## 배포 (Railway)

```bash
# 컨테이너 빌드 + 푸시 + 재시작
railway up --service signal-desk-api --ci

# 로그
railway logs --service signal-desk-api

# 환경변수 보기/설정
railway variables --service signal-desk-api
railway variables --service signal-desk-api --set KEY=VALUE
```

빌드는 `Dockerfile`(`eclipse-temurin:21` 기반 멀티스테이지, `bootJar` → JRE 런타임). `railway.json`은 `DOCKERFILE` 빌더만 지정한다.

PostgreSQL은 Railway 프로젝트에 PostgreSQL 플러그인을 어태치하면 `DATABASE_URL`/`PG*`이 자동 주입돼서 별도 설정 없이 JDBC 모드로 동작한다.

## OAuth 연동 (백엔드 측 확인사항)

소셜 로그인은 **앱이 ID 토큰을 받아서 백엔드에 POST**하는 방식. 백엔드는 토큰을 공식 엔드포인트로 검증한 뒤 자체 JWT를 발급한다.

| 엔드포인트 | 받는 값 | 검증 방법 |
|-----------|--------|----------|
| `POST /auth/oauth/google` | Google `id_token` | `https://oauth2.googleapis.com/tokeninfo?id_token=...` 호출 |
| `POST /auth/oauth/kakao` | Kakao `access_token` | `https://kapi.kakao.com/v2/user/me` 호출 |

체크리스트:
- 두 엔드포인트 모두 `JdbcStoreCondition`에 묶여 있어 **JDBC 모드일 때만 활성화**된다. 파일 모드에선 404.
- `verifyGoogleToken`은 audience(aud) 검증을 하지 않는다 — 앱이 보낸 토큰의 `sub`만 신뢰. 다른 클라이언트 ID로 발급된 토큰도 통과하므로, 운영에서 외부 노출 시 `aud` 화이트리스트를 추가할 것.
- 신규 가입 시 `userRepo.saveOAuthUser` 호출 → `users` 테이블에 `google_id`/`kakao_id` 컬럼만 채우고 `password_hash`는 NULL.
- 같은 이메일로 일반 가입한 유저가 OAuth로 다시 들어오면 자동으로 `linkGoogleId` / `linkKakaoId`로 묶는다.

## 작업 규약

## 작업 규약 (개발)

- **응답 모델 변경 시**: `MarketOverviewModel.kt` → `signal-desk-app/src/types.ts` 동시 갱신.
- **새 필드는 nullable / 기본값 부여**: 구버전 앱 호환성 유지.
- **외부 호출은 무조건 try/runCatching로 감싸고 fallback 제공**. 외부 1개 죽었다고 전체 엔드포인트 500 나면 안 됨.
- **테스트**: `MarketSessionServiceTest`, `WatchAlertServiceTest`, `WorkspaceServiceTest` 같은 단위 테스트 패턴 따라서 기능 추가 시 동일 위치에 작성.

## 최근 변경 (역순)

| 커밋 | 요약 |
|------|------|
| `9ce17c3` | AlternativeSignal에 description/methodology + TradingDayStatus 추가 |
| `a5f5506` | D/W/M 캔들 시맨틱 정리 + NewsSentimentBuilder 신설 |
| `bd95e37` | 1D 차트 plausible 시뮬레이션으로 교체 |
| `3da885a` | JWT 인증 + WebSocket 실시간 시세 + user_id 스코핑 |
| `fcb10a5` | KakaoTalk 챗봇 연동 + 매도 시기 알림 + 단타 추천 |
