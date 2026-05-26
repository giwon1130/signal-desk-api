-- US 이브닝 브리프 알림 토글. NY 장 마감 직후(06:30 KST) NASDAQ/S&P 변동·top movers·실적 종합 푸시.
-- 디폴트 false — 알림 피로도 방지. 사용자가 ReminderSettingsModal에서 ON.

alter table signal_desk_alert_preferences
    add column if not exists evening_brief_enabled boolean not null default false;
