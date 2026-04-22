-- 디바이스 푸시 토큰 (Expo Push)
create table if not exists signal_desk_push_devices (
    id            uuid primary key,
    user_id       uuid not null,
    platform      varchar(16) not null,
    expo_token    text not null unique,
    last_seen_at  timestamptz not null default now()
);

create index if not exists idx_push_devices_user on signal_desk_push_devices(user_id);

-- 관심종목 알림 로그 (같은 종목+방향+날짜 재발송 방지)
create table if not exists signal_desk_push_alert_log (
    id            uuid primary key,
    user_id       uuid not null,
    ticker        varchar(32) not null,
    direction     varchar(8) not null,   -- 'UP' | 'DOWN'
    alert_date    date not null,
    change_rate   double precision not null,
    sent_at       timestamptz not null default now(),
    unique (user_id, ticker, direction, alert_date)
);

create index if not exists idx_push_alert_log_user_date on signal_desk_push_alert_log(user_id, alert_date);
