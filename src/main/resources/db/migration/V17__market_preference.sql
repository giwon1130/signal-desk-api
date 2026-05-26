-- 사용자별 투자 시장 선호. 'KR' | 'US' | 'BOTH'. 기본 BOTH(양쪽 다 보임).
-- UI 필터링용 — 푸시 알림 토글(kr_enabled/us_enabled)과 별개.
-- 예: BOTH+kr_enabled=true+us_enabled=false → UI는 양쪽 다 보이지만 알림은 KR만.

alter table signal_desk_alert_preferences
    add column if not exists market_preference varchar(8) not null default 'BOTH';
