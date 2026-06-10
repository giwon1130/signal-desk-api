-- 거래량 급증 알림 전역 토글 + 방해금지(Quiet hours) 사용자 설정.
--
-- volume_alert_enabled: 종목별 volume_alert opt-in 과 별개로, 켜면 모든 보유/관심 종목에
--   거래량 급증 알림 적용 (detector 에서 perItem OR userGlobal). 기본 ON.
-- quiet_hours_*: 켜면 야간 시간대(start~end, KST, 래퍼라운드 지원) 동안 푸시 보류. 기본 OFF.
alter table signal_desk_alert_preferences
    add column if not exists volume_alert_enabled boolean  not null default true,
    add column if not exists quiet_hours_enabled  boolean  not null default false,
    add column if not exists quiet_start_hour      smallint not null default 22,
    add column if not exists quiet_end_hour        smallint not null default 7;
