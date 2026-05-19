-- Paper Trading 기능 제거 (코드 제거 완료, 테이블 정리).
-- V1__init_schema 에서 생성된 signal_desk_paper_positions / signal_desk_paper_trades 를 제거한다.
-- V4 에서 추가된 user_id 컬럼/인덱스도 함께 사라진다 (DROP TABLE 으로 일괄 정리).

drop table if exists signal_desk_paper_trades;
drop table if exists signal_desk_paper_positions;
