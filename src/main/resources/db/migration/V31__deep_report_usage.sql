-- PRO 전용 AI 종목 심층 리포트 일일 사용량 — 비싼 호출이라 질문 쿼터와 별도 캡.
-- assistant_usage 와 동일한 조건부 upsert 패턴으로 동시성에도 한도를 넘지 않게 한다.
create table if not exists signal_desk_deep_report_usage (
    user_id      uuid not null references signal_desk_users(id) on delete cascade,
    usage_date   date not null,
    report_count int  not null default 0,
    primary key (user_id, usage_date)
);
