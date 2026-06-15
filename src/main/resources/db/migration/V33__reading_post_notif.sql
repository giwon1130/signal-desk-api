-- 리딩 새 글 알림 토글 — 구독 리더(사람·AI)의 새 글 푸시를 끌 수 있게. 기본 ON.
alter table signal_desk_alert_preferences
    add column if not exists reading_post_enabled boolean not null default true;
