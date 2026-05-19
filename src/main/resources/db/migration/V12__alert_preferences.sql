create table if not exists signal_desk_alert_preferences (
    user_id uuid primary key references signal_desk_users(id) on delete cascade,
    kr_enabled boolean not null default true,
    us_enabled boolean not null default false,
    premarket_enabled boolean not null default true,
    updated_at timestamptz not null default now()
);
