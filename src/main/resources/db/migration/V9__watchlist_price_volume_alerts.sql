-- 관심종목 가격 알림 (하한/상한) + 거래량 급증 알림 설정
alter table signal_desk_watchlist
    add column if not exists alert_below   integer default null,
    add column if not exists alert_above   integer default null,
    add column if not exists volume_alert  boolean not null default false;
