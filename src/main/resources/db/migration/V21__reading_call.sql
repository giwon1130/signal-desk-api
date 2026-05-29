-- 리딩(Leading Call) — 종목 콜 + 시황 공유 (v2.x)
-- spec: signal-desk-app/docs/leading-call-spec.md
--
-- 4 테이블: leader / follow / reading_post / reading_call
-- reading_call.entry_price 는 immutable — 작성 시점 시세 박제 (신뢰 핵심, UPDATE 금지)

-- ─── Leader (콜 작성자) ────────────────────────────────────────────────────
create table signal_desk_reading_leader (
    user_id         uuid primary key references signal_desk_users(id),
    display_name    text not null,
    bio             text not null default '',
    invite_code     text not null unique,
    status          text not null default 'PENDING' check (status in ('PENDING','APPROVED','SUSPENDED')),
    created_at      timestamptz not null default now()
);
create index idx_reading_leader_status on signal_desk_reading_leader(status);

-- ─── Follow (구독 관계) ────────────────────────────────────────────────────
create table signal_desk_reading_follow (
    leader_user_id    uuid not null references signal_desk_users(id),
    follower_user_id  uuid not null references signal_desk_users(id),
    joined_at         timestamptz not null default now(),
    primary key (leader_user_id, follower_user_id)
);
create index idx_reading_follow_follower on signal_desk_reading_follow(follower_user_id);

-- ─── ReadingPost (리딩 글 — 시황/콜 묶음) ──────────────────────────────────
create table signal_desk_reading_post (
    id              uuid primary key default gen_random_uuid(),
    leader_user_id  uuid not null references signal_desk_users(id),
    title           text not null,
    body            text not null default '',
    visibility      text not null default 'FOLLOWERS' check (visibility in ('FOLLOWERS','PUBLIC')),
    created_at      timestamptz not null default now()
);
create index idx_reading_post_leader_created on signal_desk_reading_post(leader_user_id, created_at desc);

-- ─── Call (종목 콜 — immutable entry_price) ────────────────────────────────
create table signal_desk_reading_call (
    id                  uuid primary key default gen_random_uuid(),
    post_id             uuid not null references signal_desk_reading_post(id) on delete cascade,
    leader_user_id      uuid not null references signal_desk_users(id),
    market              text not null check (market in ('KR','US')),
    ticker              text not null,
    name                text not null,
    -- 작성 시점 시세 박제 (시장 통화). 절대 UPDATE 안 함.
    entry_price         numeric(18,4) not null check (entry_price > 0),
    entry_currency      text not null check (entry_currency in ('KRW','USD')),
    entry_locked_at     timestamptz not null,
    target_return_pct   numeric(6,2),  -- null 이면 기본 +15% 기준 알림
    status              text not null default 'ACTIVE' check (status in ('ACTIVE','HIT','CLOSED')),
    hit_at              timestamptz,   -- 목표/기본 임계 첫 도달 시각
    created_at          timestamptz not null default now()
);
create index idx_reading_call_leader on signal_desk_reading_call(leader_user_id);
create index idx_reading_call_active on signal_desk_reading_call(status) where status = 'ACTIVE';
create index idx_reading_call_post on signal_desk_reading_call(post_id);
