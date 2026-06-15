-- 📈 AI 리포트 콜 — 증권사 리포트(목표주가)를 AI가 콜로 발행하는 AI 리더 + 중복방지 원장.
insert into signal_desk_users (id, email, nickname, plan) values
  ('a1f30000-0000-4000-8000-000000000003', 'ai-report@signal-desk.internal', 'AI 리포트 콜', 'PRO')
on conflict (id) do nothing;

insert into signal_desk_reading_leader (user_id, display_name, bio, invite_code, status, is_ai) values
  ('a1f30000-0000-4000-8000-000000000003', '📈 AI 리포트 콜',
   '증권사 공개 리포트의 목표주가를 AI가 콜로 정리합니다. 진입가는 현재가, 손절가는 AI 제안. 참고용이며 투자자문이 아닙니다.',
   'AIRPT', 'APPROVED', true)
on conflict (user_id) do nothing;

-- 처리한 리포트 중복 발행 방지(report_idx 단위).
create table if not exists signal_desk_report_call_seen (
    report_key text primary key,
    created_at timestamptz not null default now()
);
