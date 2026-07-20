-- 단일 종목 30% 제한을 제거한다.
-- 기존 리그와 신규 리그 모두 제한 없음(0)으로 통일한다.
update signal_desk_mock_league
set max_position_pct = 0
where max_position_pct <> 0;

alter table signal_desk_mock_league
    alter column max_position_pct set default 0;
