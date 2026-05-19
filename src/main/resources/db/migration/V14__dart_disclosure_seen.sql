-- OpenDART 공시 dedup 테이블. 5분 폴링 시 이미 본 rcept_no 는 skip.
-- 보유/관심 종목 매칭 여부와 무관하게 신규 공시는 모두 기록해 다음 스캔에서 재처리되지 않게 한다.

create table if not exists signal_desk_disclosure_seen (
    rcept_no    varchar(20) primary key,
    stock_code  varchar(10) not null default '',
    corp_name   varchar(200) not null,
    report_nm   varchar(500) not null,
    rcept_dt    varchar(8)  not null,
    seen_at     timestamp   not null default now()
);

create index if not exists idx_disclosure_seen_stock on signal_desk_disclosure_seen(stock_code);
create index if not exists idx_disclosure_seen_rcept_dt on signal_desk_disclosure_seen(rcept_dt desc);
