-- 시즌 규칙 알림 발송 기록 — "트리거 날짜 정확히 일치" 방식은 그날 서버가 죽어 있으면
-- 그 해 알림이 통째로 사라진다. 트리거일 이후 며칠의 catch-up 윈도우를 두고,
-- 같은 트리거에 중복 발송하지 않도록 마지막 발송일을 기록한다.
alter table signal_desk_seasonality_rules
    add column if not exists last_notified_on date default null;
