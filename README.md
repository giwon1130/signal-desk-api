# signal-desk-api

`SignalDesk` 백엔드(Kotlin + Spring Boot).

## 📚 문서

자세한 문서는 [`docs/`](docs/) 에 정리됨. 역할별 진입 경로는 [`docs/README.md`](docs/README.md) 참고.

- [아키텍처 / 백엔드 / API명세 / 데이터 모델](docs/엔지니어링/) — BE
- [배포 / 환경변수 / 장애 대응 / 보안 / 비용](docs/운영/) — DevOps / 운영

제품·디자인·UX·프론트엔드 문서는 [`signal-desk-app/docs/`](https://github.com/giwon1130/signal-desk-app/tree/main/docs) 참고.

자동화 에이전트(Claude 등) 용 노트는 [`AGENTS.md`](AGENTS.md).

## 현재 범위
- 한국 시장: `KRX` 지수/수급/차트, `DART OpenAPI` 공시 5분 폴링
- 미국 시장: `Yahoo Finance` 지수(어젯밤 종가, 1순위) → `FRED` 폴백 + `FRED` 매크로(금리·CPI 등) + `CBOE VIX`
- **합성 위험도(`compositeRisk`)** — PizzINT(0.3) + VIX(0.5) + 뉴스 키워드(0.2) 가중으로 1~10 산출. score≥8 시 08:32 KST 푸시 알림 (V15 마이그레이션 `composite_risk_enabled` 토글)
- **근거 기반 시황·브리프** — 관측 시각을 검증한 한국/미국 지수, 반도체·해외 상장 프록시, 환율, 미 국채 금리, 유가·VIX, 최근 뉴스로 고정 규칙 분석. Gemini는 허용된 동의어 문구만 선택하며 방향·숫자를 바꾸지 않는다. 키가 없어도 규칙 기반 문장을 제공한다.
- **시간대별 브리프** — 마감(15:40 KST)·US 이브닝(06:30 KST) 브리프. 각각 알림 토글 (모닝=`premarket_enabled`, 마감=`close_brief_enabled` 기본 ON, 이브닝=`evening_brief_enabled`). 장중(12:30 KST) 정기 브리프는 제거됨(`MIDDAY` 슬롯은 수동 refresh 용으로만 잔존)
- **모의투자 리그** — 친구끼리 가상 자본으로 매매 경쟁. immutable trade 원장 + derived position, 리더보드. (`/api/v1/league/**`)
- **리딩(애널리스트 콜)** — 리더가 종목 콜을 게시(작성 시점 시세 `entry_price` 박제), 구독자 피드 + 성과 추적. (`/api/v1/reading/**`)
- 관심종목 이상징후: 가격 급등락, 뉴스 집중, AI 추천 정렬, 보유 손익 경고를 묶은 `watchAlerts`
- 한국/미국 장 상태 계산 (정규장/장전/장후/휴장, 반일장 캘린더 반영)
- 차트 응답에 OHLC/거래량 포함
- 뉴스: `Google News RSS`
- 한국 종목 현재가 일부: `Naver Finance Realtime`
- 사용자 데이터 저장: 관심종목, 포트폴리오, AI 추천/성과 로그

## 역할
- 한국/미국 시장 데이터를 수집해 웹과 앱에 공통 응답 제공
- 한국/미국 장 상태를 정규장/장전/장후/휴장 기준으로 계산
- 뉴스 군집화, 시장 요약, 차트 데이터, 수급 데이터를 한 번에 조합
- `VenueSignalCollector`로 bar venue collector 확장 포인트 분리
- 관심종목, 포트폴리오, AI 추천 로그를 워크스페이스 단위로 관리
- 모의투자는 리그(league) 기능으로 제공 (페이퍼 트레이딩은 V13에서 제거)

## 저장 구조
- 시장 데이터는 외부 공개 데이터 소스 호출 결과를 조합
- 워크스페이스 데이터는 `SignalDeskWorkspaceRepository` 뒤로 추상화
- 기본 모드는 로컬 파일 저장소(`signal-desk.store.mode=file`)
- 기본 저장 경로: `./data/signal-desk-store.json`
- JDBC 저장소 모드(`signal-desk.store.mode=jdbc`)도 지원
- JDBC 모드에서는 애플리케이션 기동 시 필요한 테이블을 자동 생성
- 애플리케이션 내부에서는 `SignalDeskWorkspaceRepository` 인터페이스 뒤로 추상화
- PostgreSQL 연결 시 파일 저장 없이 워크스페이스 CRUD를 그대로 사용할 수 있음

## 주요 API
조회 API:
- `GET /health`
- `GET /api/v1/market/summary` — `compositeRisk`, `marketSessions`, `watchAlerts` 포함
- `GET /api/v1/market/sections`
- `GET /api/v1/market/watchlist`
- `GET /api/v1/market/portfolio`
- `GET /api/v1/market/ai-recommendations`
- `GET /api/v1/market/top-movers?limit=10`
- `GET /api/v1/media/morning-brief` — 모닝 브리프 (공통 근거 기반 분석)
- `GET /api/v1/insights/today` — 근거·관측 시각·누락 상태·규칙 버전 포함 시황
- `GET /api/v1/insights/evaluation?days=30` — 운영자 JWT 전용, JDBC 모드의 읽기 전용 재생/탐색 평가 (1~90일)
- `GET /api/v1/disclosures/recent` — DART 공시 (5분 폴링, 보유/관심 종목)

전체 엔드포인트 목록은 [`docs/엔지니어링/API명세.md`](docs/엔지니어링/API명세.md) 참고.

`/summary`에는 `compositeRisk`, `marketSessions`, `watchAlerts`가 포함된다.
- `compositeRisk`: 1~10 위험도 + 컴포넌트(PizzINT/VIX/뉴스) 점수
- `KR`/`US`: 한국·미국 장 상태

`/sections`의 지수 기간 데이터(`periods.points`)에는 `open`, `high`, `low`, `close`, `volume` 포함.

## 사용자 저장 API
- 워크스페이스 CRUD API:
- `POST/DELETE /api/v1/workspace/watchlist`
- `POST/DELETE /api/v1/workspace/portfolio`
- `POST/DELETE /api/v1/workspace/ai/picks`
- `POST/DELETE /api/v1/workspace/ai/track-records`

## 성능 전략
- 시장 코어 데이터: 60초 메모리 캐시
- 뉴스 데이터: 5분 메모리 캐시
- 분리 endpoint로 프론트 탭 지연 로딩 지원
- 외부 소스 호출 병렬 처리

## 외부 데이터 소스
- 한국 지수/수급: `KRX 정보데이터시스템`
- 한국 종목 현재가 일부: `Naver Finance Realtime`
- 한국 공시: `DART OpenAPI` (5분 폴링)
- 미국 지수: `Yahoo Finance` v8 chart (어젯밤 종가 반영, 1순위) → `FRED` 폴백
- 미국 매크로(금리·CPI·환율·유가·금 등): `FRED`
- 글로벌 지수·선물(닛케이·항셍·S&P선물): `Yahoo Finance` v8 chart
- 미국 공포지표: `CBOE VIX`
- 뉴스: `Google News RSS`
- Gemini API: 시황의 허용 문구 선택 (`GEMINI_API_KEY`, 선택 사항)

## 시황 검증과 야간선물 연결 상태

`market-evidence-v2`는 수익률 예측 모델이 아닌 현재 조건 설명 규칙이다. 결측 비중을 재배분하지 않으며, 월간·무시각·합성 자료를 단기 방향 판단에서 제외한다.

- JDBC에서는 시간당 첫 입력/규칙 결과를 변경 없이 90일 보관한다. 평가 API는 최대 2,160건을 재생하며 절단 여부, 동일 버전 재현 실패, 지표별 누락·지연 상태를 반환한다. 실시간 시세나 Gemini로 과거 입력을 보충하지 않는다.
- 탐색 평가는 한국 거래일 06:30~09:00의 마지막 기록을 날짜당 한 번만 사용한다. 해당 일의 정규장 종료 후 실제 Naver 일봉의 시초가/직전 거래일 종가와 비교하며, 중립·자료 부족·위험 경보는 판단 보류로 집계한다. 원본 뉴스/입력은 관리자 응답에도 포함하지 않는다.
- 방향 표본 30일 미만 또는 조회 절단 시 일치율을 숨긴다. 이후에도 탐색적 일치율, Wilson 구간, 항상 상승으로 보는 기준선만 제시하며 가중치를 자동 변경하지 않는다. 테스트의 합성 입력은 실전 성과가 아니다.
- 기존 장전 프록시 예측은 별도 모델이다. V48부터 09:00 이후 기록·기존 기록 덮어쓰기·다른 규칙 버전의 통계 혼합을 막는다. 과거 기록은 삭제하지 않고 기존 통계에서 분리한다.

한국 야간선물은 **실제 수신 미연결** 상태다. `KrxNightFuturesFeed`는 읽기 전용 포트이며 운영 빈이 기본 등록되지 않는다. 한국투자증권의 [공식 H0MFCNT0 계약](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_futureoption/krx_ngt_futures_ccnl/krx_ngt_futures_ccnl.py)에 따른 디코더와 세션·시각·가격·만기 검증만 구현되어 있다. 인증/웹소켓/월물 마스터 연결은 아직 하지 않았다.

원문에는 시간이 있지만 날짜가 없으므로 수신기가 확인한 세션 시작일과 정확한 코스피200 계약/만기 정보가 필수다. 야간 휴장 여부는 [KRX 안내](https://open.krx.co.kr/contents/OPN/01/01041401/Guide_to_Night_Session_in_KRX_Derivatives_Market.pdf)의 시작일 기준이며, 현재 야간 검증의 휴장일 달력은 2026년만 허용한다. 2027년은 공식 달력 갱신 전까지 제외한다.

실제 연결 전에는 제공사의 계정·시세 권한과 앱 이용자에게 제공할 권한을 확인해야 한다. [KRX 시장정보 안내](https://data.krx.co.kr/inc/datasale/Market%20Data%20Product%20Brochure.pdf?v=20250732)는 내부 이용과 외부 재배포의 계약을 구분한다. 개인 API 키 보유가 앱 재배포 권한을 의미하지 않는다. 이 모듈에는 계좌/주문 기능이 없으며 비밀값을 보관하거나 로그로 출력하지 않는다.

## 합성 위험도(Composite Risk) 구조
- `CompositeRiskService` — PizzINT + VIX + 뉴스 키워드를 VIX 중심 가중(0.5/0.3/0.2)으로 종합해 1~10 점수 산출
- `MarketSummaryResponse.compositeRisk` 필드로 응답 임베드
- `CompositeRiskAlertService`/`Scheduler` — score≥8 임계치 시 08:32 KST 푸시 알림
- V15 마이그레이션: `composite_risk_enabled` 디바이스 단위 토글
- 기존 alternative signals(PizzINT 3종)은 단독 카드 폐기, 합성 위험도로 통합

## 실행
```bash
./gradlew bootRun
```

파일 저장 모드(기본값):
```yaml
signal-desk:
  store:
    mode: file
    path: ./data/signal-desk-store.json
```

JDBC 저장 모드 예시:
```bash
export SIGNAL_DESK_STORE_JDBC_URL=jdbc:postgresql://localhost:5432/signal_desk
export SIGNAL_DESK_STORE_JDBC_USERNAME=postgres
export SIGNAL_DESK_STORE_JDBC_PASSWORD=postgres
./gradlew bootRun --args='--signal-desk.store.mode=jdbc'
```

Docker Compose로 JDBC 모드 빠르게 확인:
```bash
docker compose -f docker-compose.jdbc.yml up --build
```

- 이 compose는 `localhost:8091`을 사용하므로, 루트 `signal-desk/docker-compose.yml`과 동시에 실행하지 않는다.

- PostgreSQL: `localhost:5432`
- API: `localhost:8091`
- 초기 스키마: `db/init/001_signal_desk_workspace.sql`
- 샘플 데이터: `db/init/002_signal_desk_workspace_seed.sql`

초기화 흐름:
1. Postgres 컨테이너가 처음 올라올 때 `db/init/*.sql`을 순서대로 실행
2. 애플리케이션은 `--signal-desk.store.mode=jdbc`로 JDBC 저장소 활성화
3. 이후 `/api/v1/workspace/*` CRUD가 파일 저장 대신 PostgreSQL에 반영

로컬 검증 예시:
```bash
curl -s http://localhost:8091/api/v1/market/watchlist
curl -s http://localhost:8091/api/v1/market/portfolio
```

루트 통합 compose에서 web + api를 함께 올릴 때는 `signal-desk/docker-compose.yml`을 사용한다.
- 기본 모드: 파일 저장소
- `signal-desk-web`은 `GET /api/v1/market/summary` healthcheck가 성공한 뒤 기동한다.
- 컨테이너 이미지에는 healthcheck용 `wget`을 포함했다.

## 테스트
```bash
./gradlew test
```

## Railway 배포
- 운영 URL: `https://signal-desk-api-production.up.railway.app`
- 상태 확인: `GET /health`
- 실제 데이터 확인: `GET /api/v1/market/summary`
- Railway 프로젝트에는 `signal-desk-api` 서비스와 `Postgres` 서비스를 함께 둔다.
- `SIGNAL_DESK_STORE_MODE=jdbc`를 사용하면 Railway PostgreSQL 변수 기준으로 JDBC 저장소가 활성화된다.
- `DATABASE_URL`, `JDBC_DATABASE_URL`, `PGHOST`, `PGDATABASE`, `PGPORT`, `PGUSER`, `PGPASSWORD` 중 사용 가능한 값을 읽어 접속한다.
- Neon Free 같은 scale-to-zero DB를 사용할 때는 `SIGNAL_DESK_MARKET_CACHE_WARM_ENABLED=false`(기본값)를 유지한다. 트래픽이 없는 시간에도 외부 시세 캐시를 미리 갱신해야 할 때만 `true`로 켠다.
- DB 폴링은 비용 절감을 위해 리그 30분, 공시·알림 15분 주기로 동작하며 :00/:15/:30/:45 경계에 맞춘다. 알림 지연 허용 범위를 바꾸려면 해당 스케줄러와 테스트 계약을 함께 수정한다.

자동배포:
- Railway 대시보드에서 `signal-desk-api` 서비스에 GitHub repo `giwon1130/signal-desk-api`를 연결한다.
- 브랜치는 `main`으로 두고 Auto Deploy를 켠다.
- 이후 `main` push 시 Railway가 Dockerfile 기준으로 자동 배포한다.

CORS:
- 로컬 개발 환경(`localhost`, `127.0.0.1`)은 기본 허용된다.
- 앱/웹 배포 도메인을 추가하려면 `SIGNAL_DESK_CORS_ALLOWED_ORIGINS`에 쉼표 구분으로 넣는다.
- 예: `SIGNAL_DESK_CORS_ALLOWED_ORIGINS=https://app.example.com,https://staging.example.com`

## 다음 확장
1. 미국 개별 종목 실데이터 범위 확대
2. 종목 전체 검색/페이징
3. 합성 위험도 가중치 자동 튜닝
4. AI 추천 자동 산출/성과 자동 계산
5. 알림 채널 확장 (보유종목 공시 + 위험도 + 모닝 브리프)
