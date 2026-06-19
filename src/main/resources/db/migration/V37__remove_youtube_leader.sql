-- 삼프로TV 유튜브 자막 요약 기능 폐지 (저작권/부정경쟁 리스크).
-- AI 리더 '📺 삼프로 AI요약'(a1f20000-...-0002) 와 그 글/구독을 제거한다.
-- reading_call 은 reading_post 의 FK(on delete cascade)로 함께 삭제된다.
delete from signal_desk_reading_post   where leader_user_id = 'a1f20000-0000-4000-8000-000000000002';
delete from signal_desk_reading_follow where leader_user_id = 'a1f20000-0000-4000-8000-000000000002';
delete from signal_desk_reading_leader where user_id        = 'a1f20000-0000-4000-8000-000000000002';

-- 과거 source='YOUTUBE'(레거시) media_summary 도 제거 — 더 이상 노출/생성하지 않음.
delete from signal_desk_media_summaries where source = 'YOUTUBE';

-- 합성 유저(ai-youtube@signal-desk.internal)는 타 테이블 FK 안전을 위해 남겨둔다(노출 없음).
