-- 관심종목 급등락 알림 회고 강화:
--   reason  : '왜 움직였나' 한 줄 사유 (UP/DOWN 알림에만, Gemini 생성). 앱 알림함에서 노출.
--   read_at : 사용자가 알림함을 열어 읽은 시각. NULL = 안 읽음(배지 카운트 대상).
alter table signal_desk_push_alert_log add column if not exists reason  text;
alter table signal_desk_push_alert_log add column if not exists read_at timestamptz;

-- 안 읽음 알림 조회(배지/목록)용 인덱스.
create index if not exists idx_alert_log_user_unread
    on signal_desk_push_alert_log (user_id, read_at);
