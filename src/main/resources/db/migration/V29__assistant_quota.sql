-- AI 비서 사용자별 일일 질문 한도 — 무료/유료 차등 + 사용량 카운트.
--  plan: FREE(기본) / PRO. 결제 인프라가 붙기 전까지는 수동 변경(운영자 SQL).
alter table signal_desk_users
    add column if not exists plan varchar(16) not null default 'FREE';

create table if not exists signal_desk_assistant_usage (
    user_id        uuid not null references signal_desk_users(id) on delete cascade,
    usage_date     date not null,    -- KST 기준
    question_count int  not null default 0,
    primary key (user_id, usage_date)
);
