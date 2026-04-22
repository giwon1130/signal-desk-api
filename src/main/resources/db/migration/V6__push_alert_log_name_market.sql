-- 알림 히스토리 뷰용 — 푸시 로그에 종목명/시장 저장
alter table signal_desk_push_alert_log add column if not exists market varchar(16);
alter table signal_desk_push_alert_log add column if not exists name   varchar(128);
