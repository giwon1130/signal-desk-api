-- 시즈널리티 알고리즘 포트폴리오 — 사용자가 저장한 '월별 시즌 규칙'.
-- 해당 월이 다가오면(스케줄러) 푸시로 알린다. BUY_MONTH=강세 진입, AVOID_MONTH=약세 회피.
create table if not exists signal_desk_seasonality_rules (
    id            uuid primary key,
    user_id       uuid not null,
    market        varchar(8)   not null,
    ticker        varchar(32)  not null,
    name          varchar(128),
    kind          varchar(16)  not null,   -- BUY_MONTH / AVOID_MONTH
    month         int          not null,   -- 1~12
    mean_pct      double precision,        -- 저장 시점 통계 스냅샷(표시·근거용)
    win_rate_pct  double precision,
    sample_years  int,
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now()
);

-- 같은 종목·종류·월은 한 사용자당 하나(재저장 시 갱신).
create unique index if not exists uq_season_rule
    on signal_desk_seasonality_rules (user_id, market, ticker, kind, month);

-- 스케줄러: 종류+월로 due 규칙 조회.
create index if not exists idx_season_rule_due
    on signal_desk_seasonality_rules (kind, month) where enabled;
