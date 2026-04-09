# signal-desk-api

`SignalDesk` 백엔드(Kotlin + Spring Boot).

## 1차 릴리즈 범위
- 한국 시장: `KRX` 지수/수급/차트
- 미국 시장: `FRED` 지수 + `CBOE VIX`
- 뉴스: `Google News RSS`
- 한국 종목 현재가 일부: `Naver Finance Realtime`
- 사용자 데이터 저장
- 관심종목
- 포트폴리오
- AI 추천/성과
- 모의투자 보유종목/거래

## 주요 API
- `GET /api/v1/market/summary`
- `GET /api/v1/market/sections`
- `GET /api/v1/market/news`
- `GET /api/v1/market/watchlist`
- `GET /api/v1/market/portfolio`
- `GET /api/v1/market/ai-recommendations`
- `GET /api/v1/market/paper-trading`
- `GET /api/v1/market/overview`

## 사용자 저장 API
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

## 실행
```bash
./gradlew bootRun
```

## 테스트
```bash
./gradlew test
```

## 다음 확장
1. 미국 개별 종목 실데이터 범위 확대
2. 종목 전체 검색/페이징
3. 저장소를 PostgreSQL로 전환
4. AI 추천 자동 산출/성과 자동 계산
