-- 마감 브리프(15:40 KST)를 기본 ON 으로 전환.
-- V22 에서 default false 로 추가했으나 토글 UI 가 아직 미배포라 사용자가 의도적으로 끈 경우가 없음 →
-- 기존 행 일괄 ON + 컬럼 default 도 true 로. (장중/이브닝은 false 유지)

alter table signal_desk_alert_preferences
    alter column close_brief_enabled set default true;

update signal_desk_alert_preferences
    set close_brief_enabled = true;
