-- 콜 결착가 박제 — HIT/CLOSED 시점 가격을 고정해 이후 시세 변동에도 수익률/적중이 흔들리지 않게.
-- (entry_price 가 immutable 인 것과 동일한 신뢰 원칙. 결착 전에는 null.)
alter table signal_desk_reading_call
    add column if not exists hit_price numeric(18,4);
