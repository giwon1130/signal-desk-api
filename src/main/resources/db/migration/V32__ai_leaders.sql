-- AI 리더 — 리딩 시스템에 AI를 진짜 "리더"로 태운다(피드/구독/적중률 인프라 재사용).
-- 구독은 PRO 전용(앱·서비스에서 게이트). is_ai 로 둘러보기에서 배지 구분.
alter table signal_desk_reading_leader add column if not exists is_ai boolean not null default false;

-- 합성 유저 2개(AI 리더용). password/google/kakao null, plan 무관.
insert into signal_desk_users (id, email, nickname, plan) values
  ('a1f10000-0000-4000-8000-000000000001', 'ai-flow@signal-desk.internal', '시데 AI 시황', 'PRO'),
  ('a1f20000-0000-4000-8000-000000000002', 'ai-youtube@signal-desk.internal', '삼프로 AI요약', 'PRO')
on conflict (id) do nothing;

-- AI 리더 2개 (APPROVED, is_ai=true).
insert into signal_desk_reading_leader (user_id, display_name, bio, invite_code, status, is_ai) values
  ('a1f10000-0000-4000-8000-000000000001', '🤖 시데 AI 시황',
   '시장 데이터(섹터·수급·급등락·지수)를 AI가 읽어 매 거래일 장전·마감 흐름을 정리합니다.',
   'AIFLOW', 'APPROVED', true),
  ('a1f20000-0000-4000-8000-000000000002', '📺 삼프로 AI요약',
   '삼프로TV 방송을 AI가 요약해 핵심만 전달합니다(원문 링크 포함).',
   'AIYTUB', 'APPROVED', true)
on conflict (user_id) do nothing;
