-- 합성 위험도(Composite Risk) 알림 토글.
-- score >= 8 일 때 모닝브리프 시간대(08:32 KST)에 전용 푸시를 보낼지 여부. 기본 ON.

alter table signal_desk_alert_preferences
    add column if not exists composite_risk_enabled boolean not null default true;
