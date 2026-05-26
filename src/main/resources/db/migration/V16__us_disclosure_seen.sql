-- SEC EDGAR US 공시 dedup. 5분 폴링 시 이미 본 accession_no 는 skip.
-- 보유/관심 종목 매칭 여부와 무관하게 신규 공시는 모두 기록해 다음 스캔에서 재처리되지 않게 한다.

CREATE TABLE IF NOT EXISTS signal_desk_us_disclosure_seen (
    accession_no varchar(32) PRIMARY KEY,
    cik          varchar(10) NOT NULL,
    ticker       varchar(10),
    form_type    varchar(20) NOT NULL,
    company_name varchar(255) NOT NULL,
    filed_at     varchar(40),
    seen_at      timestamp   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_us_disclosure_seen_ticker  ON signal_desk_us_disclosure_seen(ticker);
CREATE INDEX IF NOT EXISTS idx_us_disclosure_seen_seen_at ON signal_desk_us_disclosure_seen(seen_at DESC);
