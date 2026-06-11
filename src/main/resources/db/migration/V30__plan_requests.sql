-- PRO 신청 — 결제 인프라 전의 수동 전환 퍼널.
-- 사용자가 신청 → 운영자 콘솔에서 승인/보류. 사용자당 활성 신청 1건(재신청은 upsert).
-- 유료 수요가 데이터로 남는다(누가·언제 원했는지) — 유료화 시점 판단 근거.
create table if not exists signal_desk_plan_requests (
    user_id      uuid primary key references signal_desk_users(id) on delete cascade,
    status       varchar(16) not null default 'PENDING',  -- PENDING / APPROVED / DISMISSED
    requested_at timestamptz not null default now(),
    resolved_at  timestamptz
);
