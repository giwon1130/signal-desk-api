-- Preserve legacy mixed-KST dates verbatim. Do not relabel them as verified exchange closes.
create table signal_desk_market_close_snapshot (
    market varchar(8) not null check (market in ('KR', 'US')),
    trading_date date not null,
    session_closes_at timestamptz not null,
    captured_at timestamptz not null,
    indices jsonb not null,
    primary key (market, trading_date),
    check (captured_at >= session_closes_at)
);

alter table signal_desk_ai_pick_history
    add column analysis_date date,
    add column generated_at timestamptz,
    add column rules_version varchar(64),
    add column reference_close numeric(30,8),
    add column price_basis varchar(64) not null default 'LEGACY_UNVERIFIED',
    add column currency varchar(3),
    add column source varchar(32),
    add column assessment jsonb;
comment on column signal_desk_ai_pick_history.reference_close is
    'Reference close for the analysis date, NOT an executable entry price; generated_at is after session close.';

alter table signal_desk_daily_portfolio_snapshot
    alter column evaluation_amount type numeric(38,16),
    alter column cost_amount type numeric(38,16),
    alter column profit_amount type numeric(38,16),
    add column currency varchar(3),
    add column price_basis varchar(64) not null default 'LEGACY_UNVERIFIED',
    add column session_closes_at timestamptz,
    add column holdings_observed_at timestamptz,
    add column price_sources jsonb;
comment on column signal_desk_daily_portfolio_snapshot.holdings_observed_at is
    'Holdings observed at capture time, valued using dated close prices; not a historical holdings ledger.';
