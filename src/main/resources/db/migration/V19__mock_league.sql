-- Trading League — 친구 모의투자 게임 (v2.1)
-- spec: signal-desk-app/docs/mock-investment-game-spec.md
--
-- 4 테이블: league / participant / trade / reaction
-- Trade 는 immutable — 백엔드 코드에 UPDATE/DELETE 금지 (정정은 새 trade)
-- Position 은 trade 합산으로 derived (DB 저장 X)

-- ─── League (시즌) ─────────────────────────────────────────────────────────
create table signal_desk_mock_league (
    id                  uuid primary key default gen_random_uuid(),
    name                text not null,
    host_user_id        uuid not null references signal_desk_users(id),
    join_code           text not null unique,
    market_scope        text not null check (market_scope in ('KR','US','BOTH')),
    currency            text not null check (currency in ('KRW','USD')),
    starting_capital    bigint not null check (starting_capital > 0),
    started_at          timestamptz not null,
    ends_at             timestamptz not null,
    status              text not null default 'DRAFT' check (status in ('DRAFT','OPEN','RUNNING','FINISHED')),
    trading_hours       text not null default 'MARKET_HOURS_ONLY' check (trading_hours in ('MARKET_HOURS_ONLY','ALWAYS')),
    fee                 numeric(6,5) not null default 0.003,
    max_position_pct    numeric(4,3) not null default 0.30,
    visibility          text not null default 'OPEN' check (visibility in ('OPEN','CLOSED')),
    created_at          timestamptz not null default now(),
    check (ends_at > started_at)
);
create index idx_mock_league_host on signal_desk_mock_league(host_user_id);
create index idx_mock_league_status on signal_desk_mock_league(status);
-- auto-start/finish cron scheduler 가 status+시간으로 폴링.
create index idx_mock_league_ends_at on signal_desk_mock_league(ends_at) where status = 'RUNNING';
create index idx_mock_league_starts_at on signal_desk_mock_league(started_at) where status = 'OPEN';

-- ─── Participant ───────────────────────────────────────────────────────────
create table signal_desk_mock_participant (
    league_id           uuid not null references signal_desk_mock_league(id) on delete cascade,
    user_id             uuid not null references signal_desk_users(id),
    joined_at           timestamptz not null default now(),
    nickname            text not null,
    avatar_emoji        text not null default '🐱',
    cash_balance        bigint not null,
    final_return_rate   numeric(8,5),
    final_rank          int,
    primary key (league_id, user_id)
);
create index idx_mock_participant_user on signal_desk_mock_participant(user_id);

-- ─── Trade (immutable 체결) ────────────────────────────────────────────────
-- 환율 lock 컬럼은 spec §17 — 다국적 league(KRW/USD) 지원 위해.
create table signal_desk_mock_trade (
    id                  uuid primary key default gen_random_uuid(),
    league_id           uuid not null references signal_desk_mock_league(id) on delete cascade,
    user_id             uuid not null references signal_desk_users(id),
    market              text not null check (market in ('KR','US')),
    ticker              text not null,
    name                text not null,
    side                text not null check (side in ('BUY','SELL')),
    quantity            int not null check (quantity > 0),
    -- 시장 원래 통화 기준 가격 (KR=원, US=USD * 100 cents 정수 — 또는 numeric).
    -- 단순화: numeric 로 통일. exchange_rate 적용해 notional 산출.
    original_price      numeric(18,4) not null check (original_price > 0),
    original_currency   text not null check (original_currency in ('KRW','USD')),
    -- league 통화로 환산된 단가 (원 or USD), 매수 시점 환율 lock 결과.
    price               numeric(18,4) not null check (price > 0),
    exchange_rate       numeric(10,4) not null default 1.0000 check (exchange_rate > 0),
    price_locked_at     timestamptz not null,
    -- 비용
    fee_amount          bigint not null default 0,
    notional_amount     bigint not null,  -- league 통화 정수 (KRW=원, USD=cents)
    executed_at         timestamptz not null default now()
);
create index idx_mock_trade_league_exec on signal_desk_mock_trade(league_id, executed_at desc);
create index idx_mock_trade_user_league on signal_desk_mock_trade(user_id, league_id);
create index idx_mock_trade_league_user_ticker on signal_desk_mock_trade(league_id, user_id, ticker);

-- ─── Reaction (거래 카드 이모지) ───────────────────────────────────────────
create table signal_desk_mock_reaction (
    trade_id            uuid not null references signal_desk_mock_trade(id) on delete cascade,
    user_id             uuid not null references signal_desk_users(id),
    emoji               text not null check (emoji in ('👍','🔥','😱','🤔')),
    created_at          timestamptz not null default now(),
    primary key (trade_id, user_id, emoji)
);
