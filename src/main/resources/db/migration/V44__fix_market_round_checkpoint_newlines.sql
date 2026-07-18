-- V43의 PostgreSQL 일반 문자열에서는 \n이 줄바꿈이 아닌 문자 그대로 저장됐다.
-- 이미 배포된 첫 라운드의 체크리스트도 실제 줄바꿈으로 정규화한다.
update signal_desk_market_rounds
set checkpoints = replace(checkpoints, E'\\n', chr(10)), updated_at = now()
where id = 'semiconductor-volatility-2026-07'
  and checkpoints like E'%\\n%';
