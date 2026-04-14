# signal-desk-api

`SignalDesk` 백엔드(Kotlin + Spring Boot).

## 1차 릴리즈 범위
- 한국 시장: `KRX` 지수/수급/차트
- 미국 시장: `FRED` 지수 + `CBOE VIX`
- 실험 지표: `PizzINT` 기반 Pentagon Pizza Index / Policy Buzz 프록시
- 한국/미국 장 상태 계산
- 정규장/장전/장후/휴장
- 미국장 휴장/조기종료(반일장) 캘린더 반영
- 차트 응답에 OHLC/거래량 포함
- 뉴스: `Google News RSS`
- 한국 종목 현재가 일부: `Naver Finance Realtime`
- 사용자 데이터 저장
- 관심종목
- 포트폴리오
- AI 추천/성과
- AI 추천 근거/성과 실행 로그
- 모의투자 보유종목/거래

## 역할
- 한국/미국 시장 데이터를 수집해 웹과 앱에 공통 응답 제공
- 한국/미국 장 상태를 정규장/장전/장후/휴장 기준으로 계산
- 뉴스 군집화, 시장 요약, 차트 데이터, 수급 데이터를 한 번에 조합
- 관심종목, 포트폴리오, AI 추천 로그, 모의투자 데이터를 워크스페이스 단위로 관리

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
- `GET /api/v1/market/summary`
- `GET /api/v1/market/sections`
- `GET /api/v1/market/news`
- `GET /api/v1/market/watchlist`
- `GET /api/v1/market/portfolio`
- `GET /api/v1/market/ai-recommendations`
- `GET /api/v1/market/paper-trading`
- `GET /api/v1/market/overview`

`/summary`, `/overview`에는 `marketSessions`가 포함된다.
- `KR`: 한국 장 상태
- `US`: 미국 장 상태

`/sections`의 지수 기간 데이터(`periods.points`)에는 아래 필드가 포함된다.
- `open`, `high`, `low`, `close`, `volume`

## 사용자 저장 API
- 워크스페이스 CRUD API:
- `POST/DELETE /api/v1/workspace/watchlist`
- `POST/DELETE /api/v1/workspace/portfolio`
- `POST/DELETE /api/v1/workspace/paper/positions`
- `POST/DELETE /api/v1/workspace/paper/trades`
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
- 미국 지수: `FRED`
- 미국 공포지표: `CBOE VIX`
- 뉴스: `Google News RSS`

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

## 다음 확장
1. 미국 개별 종목 실데이터 범위 확대
2. 종목 전체 검색/페이징
3. JDBC 저장소를 실제 운영용 PostgreSQL 마이그레이션 구조로 확장
4. AI 추천 자동 산출/성과 자동 계산
