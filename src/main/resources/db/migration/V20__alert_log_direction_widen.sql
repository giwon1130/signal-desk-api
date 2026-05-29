-- 알림 스팸 근본 원인 fix (2026-05-29)
-- direction 컬럼이 varchar(8) 이라 'PRICE_ABOVE'(11) / 'PRICE_BELOW'(11) / 'VOLUME_SPIKE'(12) 를
-- INSERT 할 때 "value too long" 예외 → recordAlert 실패 → dedup 무력화 → 매 5분 재발송 스팸.
-- 컬럼을 varchar(20) 으로 확장해 모든 AlertDirection enum 값을 담을 수 있게 한다.
alter table signal_desk_push_alert_log alter column direction type varchar(20);
