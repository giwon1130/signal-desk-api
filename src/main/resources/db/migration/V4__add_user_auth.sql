-- 사용자 계정 + 인증 (이메일/구글/카카오)
create table if not exists signal_desk_users (
    id          uuid primary key,
    email       varchar(255) not null unique,
    password    text,                       -- OAuth 가입자는 null
    nickname    varchar(64) not null,
    google_id   text unique,
    kakao_id    text unique,
    created_at  timestamptz not null default now()
);

create index if not exists idx_signal_desk_users_google_id on signal_desk_users(google_id);
create index if not exists idx_signal_desk_users_kakao_id  on signal_desk_users(kakao_id);

-- 워크스페이스 데이터를 사용자별로 분리 (NULL = 공용/레거시)
alter table signal_desk_watchlist            add column if not exists user_id uuid;
alter table signal_desk_portfolio_positions  add column if not exists user_id uuid;
alter table signal_desk_paper_positions      add column if not exists user_id uuid;
alter table signal_desk_paper_trades         add column if not exists user_id uuid;
alter table signal_desk_ai_picks             add column if not exists user_id uuid;
alter table signal_desk_ai_track_records     add column if not exists user_id uuid;

create index if not exists idx_watchlist_user            on signal_desk_watchlist(user_id);
create index if not exists idx_portfolio_user            on signal_desk_portfolio_positions(user_id);
create index if not exists idx_paper_positions_user      on signal_desk_paper_positions(user_id);
create index if not exists idx_paper_trades_user         on signal_desk_paper_trades(user_id);
create index if not exists idx_ai_picks_user             on signal_desk_ai_picks(user_id);
create index if not exists idx_ai_track_records_user     on signal_desk_ai_track_records(user_id);
