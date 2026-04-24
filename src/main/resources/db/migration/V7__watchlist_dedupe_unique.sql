-- V7: 관심종목 중복 레코드 정리 + (user_id, market, ticker) 복합 유니크 제약.
--
-- 배경:
--  - 기존 saveWatchItem 이 id 공백 시 항상 새 UUID 를 생성해서, 프론트의 토글 race
--    (해제 비동기 대기 누락) 와 맞물려 같은 (user_id, market, ticker) 가 여러 id 로
--    중복 저장되어 있었다.
--
-- 전략:
--  1) 각 (user_id, market, ticker) 그룹에서 id 가 가장 작은 행 하나만 남기고 나머지 삭제.
--  2) (user_id, market, ticker) 파셜 유니크 인덱스 추가 (user_id NULL 인 레거시 글로벌 데이터도 중복 방지).

delete from signal_desk_watchlist w
using (
    select id,
           row_number() over (
               partition by coalesce(user_id::text, '__null__'), market, ticker
               order by id
           ) as rn
    from signal_desk_watchlist
) dup
where w.id = dup.id and dup.rn > 1;

-- user_id NULL 도 포함해서 중복 방지하기 위해 coalesce 기반 functional index 사용.
create unique index if not exists signal_desk_watchlist_user_market_ticker_uq
    on signal_desk_watchlist (coalesce(user_id::text, '__null__'), market, ticker);
