-- 장중(12:30)·마감(15:40) 브리프 알림 토글. 둘 다 디폴트 false (알림 피로도 방지).
-- 사용자가 ReminderSettingsModal 에서 개별 ON. (모닝=premarket_enabled, 이브닝=evening_brief_enabled 와 동일 패턴)

alter table signal_desk_alert_preferences
    add column if not exists midday_brief_enabled boolean not null default false;

alter table signal_desk_alert_preferences
    add column if not exists close_brief_enabled boolean not null default false;
