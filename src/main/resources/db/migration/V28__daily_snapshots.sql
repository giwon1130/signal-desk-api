-- 일별 데이터 적재 — "나중에 활용"을 위한 이력 누적 (개인 규모: 하루 수십 행, 연 수 MB).
--  1) 시장 스냅샷: 지수·합성위험도·무드 지표. 위험도 지표가 실제 시장과 맞았는지 사후 검증용.
--  2) AI 픽 이력: ai_picks 는 매일 덮어쓰여 과거가 안 남는다 — 추천 적중률 분석용으로 박제.
--  3) 포트폴리오 스냅샷: 사용자·시장별 일별 평가액 — 자산 추이 그래프용. (KRW/USD 합산 불가 → 시장별 행)

create table if not exists signal_desk_daily_market_snapshot (
    snapshot_date  date primary key,
    kospi          double precision,
    kospi_change   double precision,
    kosdaq         double precision,
    kosdaq_change  double precision,
    nasdaq         double precision,
    nasdaq_change  double precision,
    sp500          double precision,
    sp500_change   double precision,
    risk_score_kr  int,
    risk_score_us  int,
    -- 무드 지표 등 부가 데이터 — 스키마 변경 없이 확장 가능하게 jsonb
    metrics        jsonb,
    created_at     timestamptz not null default now()
);

create table if not exists signal_desk_ai_pick_history (
    id                   uuid primary key,
    pick_date            date not null,
    market               varchar(8)  not null,
    ticker               varchar(32) not null,
    name                 varchar(128),
    reason               text,
    confidence           int,
    expected_return_rate double precision,
    price_at_pick        double precision,   -- 적중률 판정 기준가 (스냅샷 시점 현재가)
    change_rate_at_pick  double precision,
    created_at           timestamptz not null default now(),
    unique (pick_date, market, ticker)
);

create table if not exists signal_desk_daily_portfolio_snapshot (
    user_id            uuid not null,
    snapshot_date      date not null,
    market             varchar(8) not null,
    evaluation_amount  double precision not null,
    cost_amount        double precision not null,
    profit_amount      double precision not null,
    position_count     int not null,
    created_at         timestamptz not null default now(),
    primary key (user_id, snapshot_date, market)
);
