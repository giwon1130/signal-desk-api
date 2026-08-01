-- 개인용 trader와 Signal Desk 사이의 읽기 전용 상태 브리지.
-- 토스 client_secret/계좌번호는 저장하지 않고, Signal Desk 전용 연결 키의 해시만 보관한다.
create table if not exists signal_desk_trader_connections (
    user_id         uuid primary key references signal_desk_users(id) on delete cascade,
    secret_hash     char(64) not null unique,
    secret_hint     varchar(12) not null,
    created_at      timestamptz not null default now(),
    last_seen_at    timestamptz
);

create table if not exists signal_desk_trader_snapshots (
    user_id         uuid primary key references signal_desk_users(id) on delete cascade,
    snapshot_json   text not null,
    updated_at      timestamptz not null default now()
);

create index if not exists idx_trader_connections_secret_hash
    on signal_desk_trader_connections(secret_hash);
